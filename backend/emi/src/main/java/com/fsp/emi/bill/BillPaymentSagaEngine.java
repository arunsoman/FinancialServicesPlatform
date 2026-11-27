package com.fsp.emi.bill;

import com.fsp.emi.common.ApiException;
import com.fsp.emi.common.KeyLocks;
import com.fsp.emi.common.Uuid5;
import com.fsp.emi.config.EmiProperties;
import com.fsp.emi.ledger.EmiTbResult;
import com.fsp.emi.ledger.EmiTbService;
import com.fsp.emi.wallet.Wallet;
import com.fsp.emi.wallet.WalletRepository;
import com.fsp.shared.rails.RailResult;
import com.fsp.shared.rails.RailTransportException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bill-payment saga engine (chapter 13 Stage 3, plan §5). One TigerBeetle PENDING
 * transfer + one saga row, in lockstep over the authorization-hold pattern:
 *
 * <p>Money NEVER leaves the customer's wallet until the biller confirms. {@code start}
 * places an instantly-reversible hold (DR wallet-pending, CR bill-clearing, timeout =
 * saga grace). {@code submit} sends the payment to the biller; timeout-after-submit is
 * NEVER a failure — the saga parks in {@code PENDING_RECOGNITION} where void is blocked
 * and only a recon {@code probe} or a four-eyes force-void resolves it (plan §8
 * ambiguity doctrine). Confirmed settlements post the pending (commit); rollbacks void
 * the pending (full revert). If TigerBeetle auto-expires the hold at the grace deadline,
 * commit/void observe {@code pending_transfer_expired} and the saga lands in EXPIRED —
 * funds are already back in the wallet, DB truth is repaired to match TB.</p>
 *
 * <p>Invariants (platform): DB transactions never span TigerBeetle I/O (§2d); every
 * transfer id is deterministic Uuid5 so replays are byte-identical and idempotent at
 * TB (exists ⇒ success); every transition appends a saga_event row (append-only
 * audit); every outbound provider call has a connector_call row written BEFORE the
 * network hop (exactly-once layer 3); account numbers never enter logs or events.</p>
 */
@Slf4j
@Service
public class BillPaymentSagaEngine {

    private static final int ACCOUNT_CODE_BILL_CLEARING = 300;
    private static final int JE_CODE_MOVEMENT = 1;

    public record StartResult(BillPaymentSaga saga, boolean replayed) {}
    public record SweepSummary(int holdExpiriesVoided, int submittedExpired, int probesRun, int holdVoidRetries) {}

    private final BillPaymentSagaRepository sagas;
    private final BillerRepository billers;
    private final WalletRepository wallets;
    private final SagaEventRepository events;
    private final ConnectorCallRepository calls;
    private final BillerGateway gateway;
    private final EmiTbService tb;
    private final EmiProperties props;
    private final KeyLocks locks;
    private final TransactionTemplate tx;

    public BillPaymentSagaEngine(BillPaymentSagaRepository sagas, BillerRepository billers,
                                 WalletRepository wallets, SagaEventRepository events,
                                 ConnectorCallRepository calls, BillerGateway gateway,
                                 EmiTbService tb, EmiProperties props, KeyLocks locks,
                                 TransactionTemplate tx) {
        this.sagas = sagas;
        this.billers = billers;
        this.wallets = wallets;
        this.events = events;
        this.calls = calls;
        this.gateway = gateway;
        this.tb = tb;
        this.props = props;
        this.locks = locks;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ start (place hold)

    /**
     * Places the authorization hold and creates the saga. Idempotent on
     * {@code idempotencyKey}: a replay returns the original saga untouched.
     */
    public StartResult start(UUID personId, UUID walletId, String billerCode, String accountRef,
                             long amountMinor, String idempotencyKey) {
        var replay = sagas.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            return new StartResult(replay.get(), true);
        }

        Wallet wallet = wallets.findById(walletId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EMI_WALLET_NOT_FOUND", "wallet not found"));
        if (!Wallet.Status.ACTIVE.equals(wallet.status)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMI_WALLET_NOT_ACTIVE", "wallet is not active");
        }
        Biller biller = billers.findByCode(billerCode)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EMI_BILLER_NOT_FOUND", "biller not found"));
        if (amountMinor <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_AMOUNT_INVALID", "amount must be positive");
        }
        if (amountMinor > props.getCaps().getMaxMovementMinor()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_AMOUNT_CAP", "amount exceeds movement cap");
        }
        if (!Biller.CHANNEL_BILLER_REST.equals(biller.transportChannel)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_CHANNEL_UNSUPPORTED",
                "transport channel not supported by this gateway: " + biller.transportChannel);
        }

        return locks.withLock("bill-start", walletId, () -> {
            var again = sagas.findByIdempotencyKey(idempotencyKey);
            if (again.isPresent()) {
                return new StartResult(again.get(), true);
            }

            BillPaymentSaga saga = new BillPaymentSaga();
            saga.id = UUID.randomUUID();
            saga.personId = personId;
            saga.walletId = walletId;
            saga.currency = wallet.currency;
            saga.amountMinor = java.math.BigDecimal.valueOf(amountMinor);
            saga.billerId = biller.id;
            saga.accountRef = accountRef;
            saga.idempotencyKey = idempotencyKey;
            saga.state = BillPaymentSaga.State.HOLD;
            saga.reversalClass = biller.reversalClass;
            saga.expiresAt = Instant.now().plusSeconds(props.getSagaGraceSeconds());
            saga.createdAt = Instant.now();
            saga.updatedAt = saga.createdAt;
            // Audit first (persist), then move money — the row is the saga of record.
            tx.execute(s -> {
                sagas.save(saga);
                appendEvent(saga, "SAGA_CREATED", null, BillPaymentSaga.State.HOLD, personId, null, null);
                return saga;
            });

            EmiTbResult hold = ensureHold(saga);
            return switch (hold) {
                case EmiTbResult.Ok ok -> new StartResult(saga, false);
                case EmiTbResult.DefinitiveRejection rejected -> {
                    // TB refused the hold (e.g. insufficient committed balance): no money moved;
                    // the row lands VOIDED with the rejection token as the audit reason.
                    tx.execute(s -> {
                        saga.state = BillPaymentSaga.State.VOIDED;
                        saga.lastFailureKind = "HOLD_REJECTED";
                        saga.updatedAt = Instant.now();
                        sagas.save(saga);
                        appendEvent(saga, "HOLD_REJECTED", BillPaymentSaga.State.HOLD,
                            BillPaymentSaga.State.VOIDED, personId, null, rejectionToken(rejected));
                        return saga;
                    });
                    log.warn("bill_saga_hold_rejected saga={} detail={}", saga.id, rejectionToken(rejected));
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EMI_HOLD_REJECTED",
                        "hold rejected by ledger; funds unchanged");
                }
                case EmiTbResult.UnknownTransport unknown -> {
                    // Outcome unknown — the replay of start()/submit() re-sends the SAME hold id.
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMI_TB_UNKNOWN",
                        "hold outcome unknown; retry idempotently");
                }
            };
        });
    }

    // ------------------------------------------------------------------ submit (send to biller)

    /**
     * Submits a held payment to the biller. Dispositions: Accepted/AcceptedPending →
     * SUBMITTED; Rejected/definitive → hold voided → VOIDED; ambiguous transport
     * failures → PENDING_RECOGNITION (void blocked); guard-local backpressure/throttle →
     * stays HOLD (nothing was sent). Idempotent: SUBMITTED/PENDING_RECOGNITION replays
     * return the saga untouched.
     */
    public BillPaymentSaga submit(UUID sagaId, UUID actorPersonId) {
        return locks.withLock("bill-saga", sagaId, () -> {
            BillPaymentSaga saga = sagaOrThrow(sagaId);
            switch (saga.state) {
                case BillPaymentSaga.State.SUBMITTED, BillPaymentSaga.State.PENDING_RECOGNITION -> {
                    return saga; // already at the biller — replay is a no-op
                }
                case BillPaymentSaga.State.HOLD -> { /* fall through: submit below */ }
                default -> throw new ApiException(HttpStatus.CONFLICT, "EMI_SAGA_STATE",
                    "cannot submit saga in state " + saga.state);
            }

            Biller biller = billerById(saga.billerId);
            BillRequest request = requestOf(saga);

            // Replay the hold if the original start() died mid-flight (same deterministic id).
            EmiTbResult ensured = ensureHold(saga);
            if (ensured instanceof EmiTbResult.UnknownTransport) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMI_TB_UNKNOWN",
                    "hold outcome unknown; retry idempotently");
            }

            ConnectorCall call = recordCallStart(biller, ConnectorCall.KIND_BILL_SUBMIT, saga);

            RailResult result;
            try {
                result = gateway.submit(biller, request);
            } catch (RailTransportException transport) {
                return handleTransportOnSubmit(saga, biller, call, transport, actorPersonId);
            }

            return switch (result) {
                case RailResult.Accepted a -> settled(saga, biller, call, a.railRef(), actorPersonId);
                case RailResult.AcceptedPending p -> settled(saga, biller, call, p.railRef(), actorPersonId);
                case RailResult.Rejected r -> rejectedOnSubmit(saga, biller, call, r, actorPersonId);
                case RailResult.Failed f -> failedOnSubmit(saga, biller, call, f, actorPersonId);
            };
        });
    }

    private BillPaymentSaga settled(BillPaymentSaga saga, Biller biller, ConnectorCall call,
                                    String railRef, UUID actor) {
        tx.execute(s -> {
            saga.state = BillPaymentSaga.State.SUBMITTED;
            saga.railRef = railRef;
            saga.submittedAt = Instant.now();
            saga.updatedAt = saga.submittedAt;
            sagas.save(saga);
            appendEvent(saga, "SUBMITTED", BillPaymentSaga.State.HOLD, BillPaymentSaga.State.SUBMITTED,
                actor, null, null);
            return saga;
        });
        finishCall(call, ConnectorCall.State.ACKED, railRef, null, null);
        log.info("bill_saga_submitted saga={} ref={}", saga.id, railRef);
        return saga;
    }

    /** Biller refused the payment: void the hold (money back), record, then surface the rejection. */
    private BillPaymentSaga rejectedOnSubmit(BillPaymentSaga saga, Biller biller, ConnectorCall call,
                                             RailResult.Rejected r, UUID actor) {
        finishCall(call, ConnectorCall.State.REJECTED, r.code(), "Rejected", r.code());
        resolveVoidAfterReject(saga, actor, "SUBMIT_REJECTED:" + safeCode(r.code()));
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EMI_BILL_REJECTED",
            "biller rejected the payment");
    }

    /**
     * Ladder for Failed results on submit. RateLimited/Backpressure = nothing left the
     * platform (guard-local) → HOLD, retry later. CommUnreachable observed-for==0 is the
     * breaker refusing locally (zero provider contact) → also HOLD. Everything else is
     * ambiguous by doctrine → PENDING_RECOGNITION (void henceforth blocked).
     */
    private BillPaymentSaga failedOnSubmit(BillPaymentSaga saga, Biller biller, ConnectorCall call,
                                           RailResult.Failed f, UUID actor) {
        boolean nothingSent = switch (f.kind()) {
            case RailResult.FailureKind.RateLimited ignored -> true;
            case RailResult.FailureKind.Backpressure ignored -> true;
            case RailResult.FailureKind.CommUnreachable c -> c.observedFor().isZero();
            default -> false;
        };
        if (nothingSent) {
            tx.execute(s -> {
                saga.attempt++;
                saga.lastFailureKind = f.kind().getClass().getSimpleName();
                saga.updatedAt = Instant.now();
                sagas.save(saga);
                appendEvent(saga, "SUBMIT_DEFERRED", BillPaymentSaga.State.HOLD, BillPaymentSaga.State.HOLD,
                    actor, null, f.kind().getClass().getSimpleName());
                return saga;
            });
            finishCall(call, ConnectorCall.State.FAILED, null, f.kind().getClass().getSimpleName(), null);
            log.info("bill_saga_submit_deferred saga={} kind={}", saga.id, f.kind().getClass().getSimpleName());
            return saga; // stays HOLD — nothing was sent; retry is safe
        }
        tx.execute(s -> {
            saga.state = BillPaymentSaga.State.PENDING_RECOGNITION;
            saga.lastFailureKind = f.kind().getClass().getSimpleName();
            saga.updatedAt = Instant.now();
            sagas.save(saga);
            appendEvent(saga, "SUBMIT_AMBIGUOUS", BillPaymentSaga.State.HOLD,
                BillPaymentSaga.State.PENDING_RECOGNITION, actor, null,
                f.kind().getClass().getSimpleName());
            return saga;
        });
        finishCall(call, ConnectorCall.State.FAILED, null, f.kind().getClass().getSimpleName(), null);
        log.warn("bill_saga_pending_recognition saga={} kind={}", saga.id, f.kind().getClass().getSimpleName());
        return saga;
    }

    private BillPaymentSaga handleTransportOnSubmit(BillPaymentSaga saga, Biller biller, ConnectorCall call,
                                                    RailTransportException transport, UUID actor) {
        return switch (transport.kind()) {
            // Guard never let the call out (throttle freeze / local backpressure): nothing sent.
            case RailResult.FailureKind.RateLimited ignored ->
                deferOnTransport(saga, call, transport, actor);
            case RailResult.FailureKind.Backpressure ignored ->
                deferOnTransport(saga, call, transport, actor);
            // Timeout/cut mid-call: the biller MAY have accepted — doctrine (plan §8.3):
            // PENDING_RECOGNITION, void blocked, probe resolves.
            default -> ambiguousOnTransport(saga, call, transport, actor);
        };
    }

    private BillPaymentSaga deferOnTransport(BillPaymentSaga saga, ConnectorCall call,
                                             RailTransportException transport, UUID actor) {
        tx.execute(s -> {
            saga.attempt++;
            saga.lastFailureKind = transport.kind().getClass().getSimpleName();
            saga.updatedAt = Instant.now();
            sagas.save(saga);
            appendEvent(saga, "SUBMIT_DEFERRED", BillPaymentSaga.State.HOLD, BillPaymentSaga.State.HOLD,
                actor, null, transport.kind().getClass().getSimpleName());
            return saga;
        });
        finishCall(call, ConnectorCall.State.FAILED, null, transport.kind().getClass().getSimpleName(), null);
        return saga;
    }

    private BillPaymentSaga ambiguousOnTransport(BillPaymentSaga saga, ConnectorCall call,
                                                 RailTransportException transport, UUID actor) {
        tx.execute(s -> {
            saga.state = BillPaymentSaga.State.PENDING_RECOGNITION;
            saga.lastFailureKind = transport.kind().getClass().getSimpleName();
            saga.updatedAt = Instant.now();
            sagas.save(saga);
            appendEvent(saga, "SUBMIT_AMBIGUOUS", BillPaymentSaga.State.HOLD,
                BillPaymentSaga.State.PENDING_RECOGNITION, actor, null,
                transport.kind().getClass().getSimpleName());
            return saga;
        });
        finishCall(call, ConnectorCall.State.FAILED, null, transport.kind().getClass().getSimpleName(), null);
        log.warn("bill_saga_transport_ambiguous saga={} kind={}", saga.id, transport.kind().getClass().getSimpleName());
        return saga;
    }

    // ------------------------------------------------------------------ probe (recon disambiguation)

    /**
     * Recon status probe: the ONLY sanctioned resolver for PENDING_RECOGNITION (and the
     * fast-path recognizer for SUBMITTED). settled → commit pending → CONFIRMED;
     * biller-not-found → void pending → VOIDED; anything inconclusive changes nothing.
     */
    public BillPaymentSaga probeDisambiguate(UUID sagaId, UUID actorPersonId) {
        return locks.withLock("bill-saga", sagaId, () -> {
            BillPaymentSaga saga = sagaOrThrow(sagaId);
            if (BillPaymentSaga.State.CONFIRMED.equals(saga.state)
                    || BillPaymentSaga.State.VOIDED.equals(saga.state)) {
                return saga;
            }
            if (!BillPaymentSaga.State.SUBMITTED.equals(saga.state)
                    && !BillPaymentSaga.State.PENDING_RECOGNITION.equals(saga.state)) {
                throw new ApiException(HttpStatus.CONFLICT, "EMI_SAGA_STATE",
                    "probe requires SUBMITTED or PENDING_RECOGNITION, state=" + saga.state);
            }
            Biller biller = billerById(saga.billerId);
            if (!biller.probeSupported) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_PROBE_UNSUPPORTED",
                    "biller does not support status probes");
            }

            ConnectorCall call = recordCallStart(biller, ConnectorCall.KIND_BILL_PROBE, saga);
            var probe = gateway.probe(biller, requestOf(saga), saga.railRef);
            if (probe.isEmpty()) {
                appendEventTx(saga, "PROBE_INCONCLUSIVE", null, null, actorPersonId);
                finishCall(call, ConnectorCall.State.FAILED, null, "INCONCLUSIVE", null);
                return saga;
            }
            return switch (probe.get()) {
                case RailResult.Accepted a -> {
                    finishCall(call, ConnectorCall.State.ACKED, a.railRef(), null, null);
                    yield commitPending(saga, actorPersonId, "PROBE_SETTLED");
                }
                case RailResult.Rejected r -> {
                    // Biller never saw / no longer knows this payment: hold releases. This is
                    // the probe-authoritative void — no four-eyes needed (money never settled).
                    finishCall(call, ConnectorCall.State.REJECTED, r.code(), "Rejected", r.code());
                    yield voidHold(saga, actorPersonId, null, "PROBE_NOT_FOUND:" + safeCode(r.code()));
                }
                case RailResult.AcceptedPending p -> {
                    appendEventTx(saga, "PROBE_PENDING", null, null, actorPersonId);
                    finishCall(call, ConnectorCall.State.PENDING, p.railRef(), null, null);
                    yield saga;
                }
                case RailResult.Failed f -> {
                    appendEventTx(saga, "PROBE_INCONCLUSIVE", null, null, actorPersonId);
                    finishCall(call, ConnectorCall.State.FAILED, null,
                        f.kind().getClass().getSimpleName(), null);
                    yield saga;
                }
            };
        });
    }

    // ------------------------------------------------------------------ confirm (recon "mark done")

    /** Recon-driven commit of the pending hold (ladder "mark done"). Idempotent on CONFIRMED. */
    public BillPaymentSaga confirm(UUID sagaId, UUID actorPersonId, String reason) {
        return locks.withLock("bill-saga", sagaId, () -> {
            BillPaymentSaga saga = sagaOrThrow(sagaId);
            if (BillPaymentSaga.State.CONFIRMED.equals(saga.state)) {
                return saga;
            }
            if (!BillPaymentSaga.State.SUBMITTED.equals(saga.state)
                    && !BillPaymentSaga.State.PENDING_RECOGNITION.equals(saga.state)) {
                throw new ApiException(HttpStatus.CONFLICT, "EMI_SAGA_STATE",
                    "confirm requires SUBMITTED or PENDING_RECOGNITION, state=" + saga.state);
            }
            return commitPending(saga, actorPersonId, reason);
        });
    }

    private BillPaymentSaga commitPending(BillPaymentSaga saga, UUID actor, String reason) {
        return switch (tb.createTransfers(List.of(commitSpec(saga)))) {
            case EmiTbResult.Ok ok -> {
                tx.execute(s -> {
                    saga.state = BillPaymentSaga.State.CONFIRMED;
                    saga.settledAt = Instant.now();
                    saga.updatedAt = saga.settledAt;
                    sagas.save(saga);
                    appendEvent(saga, "CONFIRMED", null, BillPaymentSaga.State.CONFIRMED, actor, null, reason);
                    return saga;
                });
                log.info("bill_saga_confirmed saga={}", saga.id);
                yield saga;
            }
            case EmiTbResult.DefinitiveRejection rejected -> {
                // TB auto-expired the hold at the grace deadline: funds already released.
                if (rejected.detail() != null && rejected.detail().contains("pending_transfer_expired")) {
                    tx.execute(s -> {
                        saga.state = BillPaymentSaga.State.EXPIRED;
                        saga.lastFailureKind = "TB_HOLD_EXPIRED";
                        saga.updatedAt = Instant.now();
                        sagas.save(saga);
                        appendEvent(saga, "TB_HOLD_EXPIRED", null, BillPaymentSaga.State.EXPIRED, actor, null, null);
                        return saga;
                    });
                    log.warn("bill_saga_tb_hold_expired saga={}", saga.id);
                    yield saga;
                }
                tx.execute(s -> {
                    saga.lastFailureKind = "COMMIT_REJECTED";
                    saga.updatedAt = Instant.now();
                    sagas.save(saga);
                    appendEvent(saga, "COMMIT_REJECTED", null, null, actor, null, rejectionToken(rejected));
                    return saga;
                });
                throw new ApiException(HttpStatus.CONFLICT, "EMI_COMMIT_REJECTED",
                    "ledger refused the commit; funds still held");
            }
            case EmiTbResult.UnknownTransport unknown -> {
                tx.execute(s -> {
                    saga.lastFailureKind = "COMMIT_UNKNOWN";
                    saga.updatedAt = Instant.now();
                    sagas.save(saga);
                    appendEvent(saga, "COMMIT_UNKNOWN", null, null, actor, null, null);
                    return saga;
                });
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMI_TB_UNKNOWN",
                    "commit outcome unknown; replay confirm idempotently");
            }
        };
    }

    // ------------------------------------------------------------------ void / force-void

    /**
     * Rolls a saga back by voiding the pending hold.
     *   HOLD                 → pre-submit cancel (actor only, no four-eyes).
     *   PENDING_RECOGNITION  → force-void ONLY: four-eyes (maker ≠ checker, both human),
     *                          audited, provisioned for claw-back reversal (plan §8.3).
     *   SUBMITTED / CONFIRMED / others → blocked.
     * Idempotent on VOIDED.
     */
    public BillPaymentSaga voidSaga(UUID sagaId, UUID makerPersonId, UUID checkerPersonId, String reason) {
        return locks.withLock("bill-saga", sagaId, () -> {
            BillPaymentSaga saga = sagaOrThrow(sagaId);
            if (BillPaymentSaga.State.VOIDED.equals(saga.state)) {
                return saga;
            }
            switch (saga.state) {
                case BillPaymentSaga.State.HOLD -> {
                    if (makerPersonId == null) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_ACTOR_REQUIRED",
                            "void requires an acting person");
                    }
                    return voidHold(saga, makerPersonId, checkerPersonId, nullOrReason(reason, "PRE_SUBMIT_VOID"));
                }
                case BillPaymentSaga.State.PENDING_RECOGNITION -> {
                    if (!isFourEyes(makerPersonId, checkerPersonId)) {
                        throw new ApiException(HttpStatus.FORBIDDEN, "EMI_FORCE_VOID_FOUR_EYES",
                            "voiding an ambiguous submission requires two distinct human approvers");
                    }
                    return voidHold(saga, makerPersonId, checkerPersonId, nullOrReason(reason, "FORCE_VOID"));
                }
                case BillPaymentSaga.State.CONFIRMED -> throw new ApiException(HttpStatus.CONFLICT,
                    "EMI_VOID_BLOCKED", "payment settled; reversal is a dispute flow (reversal class "
                    + saga.reversalClass + ")");
                default -> throw new ApiException(HttpStatus.CONFLICT, "EMI_VOID_BLOCKED",
                    "cannot void saga in state " + saga.state);
            }
        });
    }

    private boolean isFourEyes(UUID maker, UUID checker) {
        return maker != null && checker != null && !maker.equals(checker);
    }

    private static String nullOrReason(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }

    /** Issues the void-pending transfer and lands the saga. VOID_UNKNOWN defers to the sweeper. */
    private BillPaymentSaga voidHold(BillPaymentSaga saga, UUID maker, UUID checker, String reason) {
        return switch (tb.createTransfers(List.of(voidSpec(saga)))) {
            case EmiTbResult.Ok ok -> {
                tx.execute(s -> {
                    saga.state = BillPaymentSaga.State.VOIDED;
                    saga.updatedAt = Instant.now();
                    sagas.save(saga);
                    appendEvent(saga, "VOIDED", null, BillPaymentSaga.State.VOIDED, maker, checker, reason);
                    return saga;
                });
                log.info("bill_saga_voided saga={}", saga.id);
                yield saga;
            }
            case EmiTbResult.DefinitiveRejection rejected -> {
                if (rejected.detail() != null && rejected.detail().contains("pending_transfer_expired")) {
                    tx.execute(s -> {
                        saga.state = BillPaymentSaga.State.EXPIRED;
                        saga.lastFailureKind = "TB_HOLD_EXPIRED";
                        saga.updatedAt = Instant.now();
                        sagas.save(saga);
                        appendEvent(saga, "TB_HOLD_EXPIRED", null, BillPaymentSaga.State.EXPIRED, maker, checker, null);
                        return saga;
                    });
                    log.warn("bill_saga_tb_hold_expired_on_void saga={}", saga.id);
                    yield saga;
                }
                tx.execute(s -> {
                    saga.lastFailureKind = "VOID_REJECTED";
                    saga.updatedAt = Instant.now();
                    sagas.save(saga);
                    appendEvent(saga, "VOID_REJECTED", null, null, maker, checker, rejectionToken(rejected));
                    return saga;
                });
                throw new ApiException(HttpStatus.CONFLICT, "EMI_VOID_REJECTED",
                    "ledger refused the void; funds still held");
            }
            case EmiTbResult.UnknownTransport unknown -> {
                tx.execute(s -> {
                    saga.lastFailureKind = "VOID_UNKNOWN";
                    saga.updatedAt = Instant.now();
                    sagas.save(saga);
                    appendEvent(saga, "VOID_UNKNOWN", null, null, maker, checker, reason);
                    return saga;
                });
                log.warn("bill_saga_void_unknown saga={} detail={}", saga.id, reason);
                yield saga; // sweeper retries the void with the same deterministic id
            }
        };
    }

    /** Biller rejected an already-submitted payment: void the hold; UNKNOWN defers to sweeper. */
    private void resolveVoidAfterReject(BillPaymentSaga saga, UUID actor, String reason) {
        BillPaymentSaga fresh = sagaOrThrow(saga.id);
        if (!BillPaymentSaga.State.HOLD.equals(fresh.state)) {
            // A concurrent transition already moved it past HOLD; do not double-void.
            return;
        }
        switch (tb.createTransfers(List.of(voidSpec(fresh)))) {
            case EmiTbResult.Ok ok -> tx.execute(s -> {
                fresh.state = BillPaymentSaga.State.VOIDED;
                fresh.lastFailureKind = "SUBMIT_REJECTED";
                fresh.updatedAt = Instant.now();
                sagas.save(fresh);
                appendEvent(fresh, "VOIDED", BillPaymentSaga.State.HOLD, BillPaymentSaga.State.VOIDED,
                    actor, null, reason);
                return fresh;
            });
            case EmiTbResult.UnknownTransport unknown -> tx.execute(s -> {
                fresh.lastFailureKind = "VOID_UNKNOWN";
                fresh.updatedAt = Instant.now();
                sagas.save(fresh);
                appendEvent(fresh, "SUBMIT_REJECT_VOID_UNKNOWN", null, null, actor, null, reason);
                return fresh;
            });
            case EmiTbResult.DefinitiveRejection rejected -> tx.execute(s -> {
                if (rejected.detail() != null && rejected.detail().contains("pending_transfer_expired")) {
                    fresh.state = BillPaymentSaga.State.EXPIRED;
                    fresh.lastFailureKind = "TB_HOLD_EXPIRED";
                } else {
                    fresh.lastFailureKind = "VOID_REJECTED";
                }
                fresh.updatedAt = Instant.now();
                sagas.save(fresh);
                appendEvent(fresh, "VOID_REJECTED", null, null, actor, null, rejectionToken(rejected));
                return fresh;
            });
        }
    }

    // ------------------------------------------------------------------ TTL sweeper

    /**
     * Disposition sweeper (plan §5.3 "yet to reconcile"): expired pre-submit HOLD sagas
     * auto-void (nothing was sent — safe); expired SUBMITTED sagas move to
     * PENDING_RECOGNITION (void henceforth blocked, only probes/force-void resolve);
     * sagas stuck on VOID_UNKNOWN retry the void with the same deterministic id.
     */
    public SweepSummary sweepExpired() {
        int holdVoided = 0, submittedExpired = 0, probesRun = 0, holdVoidRetries = 0;
        Pageable page = PageRequest.of(0, 100);
        Instant now = Instant.now();

        for (BillPaymentSaga saga : sagas.findByStateAndExpiresAtBefore(BillPaymentSaga.State.HOLD, now, page)) {
            BillPaymentSaga fresh = locks.withLock("bill-saga", saga.id, () -> sagaOrThrow(saga.id));
            if (!BillPaymentSaga.State.HOLD.equals(fresh.state)
                    || fresh.expiresAt.isAfter(Instant.now())) {
                continue;
            }
            boolean retryingVoid = "VOID_UNKNOWN".equals(fresh.lastFailureKind);
            var result = tb.createTransfers(List.of(voidSpec(fresh)));
            switch (result) {
                case EmiTbResult.Ok ok -> tx.execute(s -> {
                    fresh.state = BillPaymentSaga.State.VOIDED;
                    fresh.lastFailureKind = "TTL_EXPIRED";
                    fresh.updatedAt = Instant.now();
                    sagas.save(fresh);
                    appendEvent(fresh, retryingVoid ? "VOID_RETRY_OK" : "TTL_EXPIRE_VOID",
                        BillPaymentSaga.State.HOLD, BillPaymentSaga.State.VOIDED, null, null, null);
                    return fresh;
                });
                case EmiTbResult.UnknownTransport unknown -> {
                    /* leave HOLD; next sweep retries */ }
                case EmiTbResult.DefinitiveRejection rejected -> tx.execute(s -> {
                    if (rejected.detail() != null && rejected.detail().contains("pending_transfer_expired")) {
                        fresh.state = BillPaymentSaga.State.EXPIRED;
                        fresh.lastFailureKind = "TB_HOLD_EXPIRED";
                    } else {
                        fresh.lastFailureKind = "VOID_REJECTED";
                    }
                    fresh.updatedAt = Instant.now();
                    sagas.save(fresh);
                    appendEvent(fresh, "SWEEP_VOID_REJECTED", null, null, null, null, rejectionToken(rejected));
                    return fresh;
                });
            }
            if (retryingVoid) {
                holdVoidRetries++;
            } else {
                holdVoided++;
            }
        }

        for (BillPaymentSaga saga : sagas.findByStateAndExpiresAtBefore(BillPaymentSaga.State.SUBMITTED, now, page)) {
            BillPaymentSaga fresh = locks.withLock("bill-saga", saga.id, () -> sagaOrThrow(saga.id));
            if (!BillPaymentSaga.State.SUBMITTED.equals(fresh.state)) {
                continue;
            }
            tx.execute(s -> {
                fresh.state = BillPaymentSaga.State.PENDING_RECOGNITION;
                fresh.lastFailureKind = "TTL_AWAIT_RECON";
                fresh.updatedAt = Instant.now();
                sagas.save(fresh);
                appendEvent(fresh, "TTL_AWAIT_RECON", BillPaymentSaga.State.SUBMITTED,
                    BillPaymentSaga.State.PENDING_RECOGNITION, null, null, null);
                return fresh;
            });
            submittedExpired++;
        }

        // Self-healing: run probe-disambiguation for all ambiguated sagas whose biller supports it.
        for (BillPaymentSaga saga : sagas.findByState(BillPaymentSaga.State.PENDING_RECOGNITION, page)) {
            Biller biller = billers.findById(saga.billerId).orElse(null);
            if (biller == null || !biller.probeSupported) {
                continue;
            }
            try {
                probeDisambiguate(saga.id, null);
                probesRun++;
            } catch (RuntimeException e) {
                log.warn("bill_saga_sweep_probe_failed saga={} err={}", saga.id, e.getMessage());
            }
        }
        if (holdVoided + submittedExpired + probesRun + holdVoidRetries > 0) {
            log.info("bill_saga_sweep holdVoided={} submittedExpired={} probesRun={} voidRetries={}",
                holdVoided, submittedExpired, probesRun, holdVoidRetries);
        }
        return new SweepSummary(holdVoided, submittedExpired, probesRun, holdVoidRetries);
    }

    // ------------------------------------------------------------------ TB spec builders

    /** The PENDING authorization hold: DR wallet (pending) → CR bill-clearing, TTL = grace. */
    private EmiTbResult ensureHold(BillPaymentSaga saga) {
        UUID clearing = Uuid5.billClearingAccountId(saga.currency);
        var accounts = tb.createAccounts(List.of(new EmiTbService.AccountSpec(
            clearing, tb.bookId(), ACCOUNT_CODE_BILL_CLEARING,
            tb.flagFor(EmiTbService.Bound.NONE, true), null, 0, 0)));
        if (!(accounts instanceof EmiTbResult.Ok)) {
            return accounts;
        }
        return tb.createTransfers(List.of(holdSpec(saga)));
    }

    private EmiTbService.TransferSpec holdSpec(BillPaymentSaga saga) {
        return new EmiTbService.TransferSpec(
            Uuid5.sagaPendingTransferId(saga.id),
            tbWalletAccount(saga),               // debit: customer wallet (pending reservation)
            Uuid5.billClearingAccountId(saga.currency), // credit: held-funds parking
            saga.amount(), tb.bookId(), JE_CODE_MOVEMENT,
            EmiTbService.FLAG_PENDING,
            saga.id, 0, 0,
            null, holdTimeoutSeconds());
    }

    private EmiTbService.TransferSpec commitSpec(BillPaymentSaga saga) {
        return new EmiTbService.TransferSpec(
            Uuid5.sagaCommitTransferId(saga.id),
            tbWalletAccount(saga), Uuid5.billClearingAccountId(saga.currency),
            saga.amount(), tb.bookId(), JE_CODE_MOVEMENT,
            EmiTbService.FLAG_POST_PENDING,
            saga.id, 0, 0,
            Uuid5.sagaPendingTransferId(saga.id), 0);
    }

    private EmiTbService.TransferSpec voidSpec(BillPaymentSaga saga) {
        return new EmiTbService.TransferSpec(
            Uuid5.sagaVoidTransferId(saga.id),
            tbWalletAccount(saga), Uuid5.billClearingAccountId(saga.currency),
            saga.amount(), tb.bookId(), JE_CODE_MOVEMENT,
            EmiTbService.FLAG_VOID_PENDING,
            saga.id, 0, 0,
            Uuid5.sagaPendingTransferId(saga.id), 0);
    }

    private int holdTimeoutSeconds() {
        return (int) Math.min(props.getSagaGraceSeconds(), Integer.MAX_VALUE);
    }

    private UUID tbWalletAccount(BillPaymentSaga saga) {
        Wallet wallet = wallets.findById(saga.walletId).orElseThrow(() ->
            new ApiException(HttpStatus.NOT_FOUND, "EMI_WALLET_NOT_FOUND", "wallet not found"));
        return wallet.tbAccountId;
    }

    // ------------------------------------------------------------------ audit + call records

    private BillPaymentSaga sagaOrThrow(UUID sagaId) {
        return sagas.findById(sagaId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EMI_SAGA_NOT_FOUND", "saga not found"));
    }

    private Biller billerById(UUID billerId) {
        return billers.findById(billerId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EMI_BILLER_NOT_FOUND", "biller not found"));
    }

    private BillRequest requestOf(BillPaymentSaga saga) {
        return new BillRequest(saga.id, saga.accountRef, saga.currency, saga.amount(), saga.idempotencyKey);
    }

    private void appendEvent(BillPaymentSaga saga, String type, String from, String to,
                             UUID actor, UUID checker, String detail) {
        SagaEvent e = new SagaEvent();
        e.id = UUID.randomUUID();
        e.sagaId = saga.id;
        e.eventType = type;
        e.fromState = from;
        e.toState = to;
        e.actorPersonId = actor;
        e.checkerPersonId = checker;
        e.detail = detail;
        e.createdAt = Instant.now();
        events.save(e);
    }

    private void appendEventTx(BillPaymentSaga saga, String type, String from, String to, UUID actor) {
        tx.execute(s -> {
            saga.updatedAt = Instant.now();
            sagas.save(saga);
            appendEvent(saga, type, from, to, actor, null, null);
            return saga;
        });
    }

    /** Writes the layer-3 RECORDED row BEFORE the network hop (exactly-once, plan §6). */
    private ConnectorCall recordCallStart(Biller biller, String kind, BillPaymentSaga saga) {
        String callKey = "bill:" + kind + ":" + saga.id;
        var existing = calls.findByKindAndIdempotencyKey(kind, callKey);
        ConnectorCall call = existing.orElseGet(() -> {
            ConnectorCall c = new ConnectorCall();
            c.id = UUID.randomUUID();
            c.connectorCode = biller.code;
            c.kind = kind;
            c.idempotencyKey = callKey;
            c.createdAt = Instant.now();
            c.updatedAt = c.createdAt;
            c.state = ConnectorCall.State.RECORDED;
            return c;
        });
        call.hostKey = "biller:" + biller.code;
        call.railRef = saga.railRef;
        call.updatedAt = Instant.now();
        return calls.save(call);
    }

    private void finishCall(ConnectorCall call, String state, String railRef, String failureKind, String failureCode) {
        call.state = state;
        if (railRef != null) call.railRef = railRef;
        if (failureKind != null) call.failureKind = failureKind;
        if (failureCode != null) call.failureCode = failureCode;
        call.updatedAt = Instant.now();
        calls.save(call);
    }

    /** Strips any biller-supplied text to a bounded token — never echoes biller bodies. */
    private static String safeCode(String code) {
        return code == null ? "UNKNOWN" : code.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String rejectionToken(EmiTbResult.DefinitiveRejection rejected) {
        String d = rejected.detail() == null ? "" : rejected.detail();
        return d.length() > 120 ? d.substring(0, 120) : d;
    }
}
