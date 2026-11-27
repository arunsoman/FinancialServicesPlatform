package com.fsp.psp.acquire;

import com.fsp.psp.audit.PspEvent;
import com.fsp.psp.audit.PspEventRepository;
import com.fsp.psp.common.ApiException;
import com.fsp.psp.common.KeyLocks;
import com.fsp.psp.common.PspUuid5;
import com.fsp.psp.config.PspProperties;
import com.fsp.psp.connector.BankConnector;
import com.fsp.psp.connector.ConnectorService;
import com.fsp.psp.ledger.PspTbResult;
import com.fsp.psp.ledger.PspTbService;
import com.fsp.psp.merchant.Merchant;
import com.fsp.psp.merchant.MerchantService;
import com.fsp.psp.outbox.PspOutbox;
import com.fsp.psp.outbox.PspOutboxRepository;
import com.fsp.psp.settlement.ClearingPosition;
import com.fsp.psp.settlement.ClearingPositionRepository;
import com.fsp.shared.rails.RailResult;
import com.fsp.shared.rails.RailTransportException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Optional;

/**
 * Merchant-acquiring saga engine (plan §11 Stage 5). PSP is pass-through — it never owns
 * merchant money or customer funds. Money flow on the PSP book (805):
 *
 * <ol>
 *   <li><b>authorize</b> — rail decision only (no platform money moves). CREATED → AUTHORIZED
 *       with a {@code rail_ref}; exactly-once anchored by the {@code rail_call} ledger.</li>
 *   <li><b>capture</b> — two-phase pending chain on the PSP book:
 *       leg0 DR settlement-float gross → CR clearing gross;
 *       leg1 DR clearing fee → CR fee-revenue fee (only when fee &gt; 0). All-but-last leg
 *       carries {@code FLAG_LINKED} ⇒ atomic all-or-nothing (platform §2f). Pending
 *       timeout = settle grace window (2 d) — the sweep beats it (PSD-1 §12.2.3).</li>
 *   <li><b>sweep</b> ({@code SettlementService}) — post-pending commits, then the
 *       {@code closing_debit} DR clearing net → CR merchant payable, which drains the
 *       clearing account back to <b>zero per settlement cycle</b> (hard invariant).</li>
 * </ol>
 *
 * <p>Dispositions: rail Rejected → REJECTED (business event); local backpressure/rate
 * limit/unavailable → stays CREATED (nothing sent; TTL sweeper retries with the SAME
 * deterministic ids); CommUnreachable → stays CREATED (outcome unknown). Unknown-transport
 * TB outcomes surface 503 PSP_TB_UNKNOWN — replay is idempotent by construction (§2d).</p>
 */
@Slf4j
@Service
public class AcquireService {

    private static final java.util.Set<String> SORTABLE = java.util.Set.of("createdAt", "pspRef", "grossMinor",
        "netMinor", "state", "cycleDate");

    private final AcquisitionRepository acquisitions;
    private final RailCallRepository calls;
    private final MerchantService merchants;
    private final ConnectorService connectors;
    private final AcquirerGateway restGateway;
    private final AcquirerGateway demoGateway;
    private final PspTbService tb;
    private final PspEventRepository events;
    private final PspOutboxRepository outbox;
    private final ClearingPositionRepository positions;
    private final PspProperties props;
    private final KeyLocks locks;
    private final TransactionTemplate tx;

    public AcquireService(AcquisitionRepository acquisitions, RailCallRepository calls,
                          MerchantService merchants, ConnectorService connectors,
                          AcquirerRestGateway restGateway, AcquirerDemoGateway demoGateway,
                          PspTbService tb, PspEventRepository events,
                          PspOutboxRepository outbox, ClearingPositionRepository positions,
                          PspProperties props, KeyLocks locks, TransactionTemplate tx) {
        this.acquisitions = acquisitions;
        this.calls = calls;
        this.merchants = merchants;
        this.connectors = connectors;
        this.restGateway = restGateway;
        this.demoGateway = demoGateway;
        this.tb = tb;
        this.events = events;
        this.outbox = outbox;
        this.positions = positions;
        this.props = props;
        this.locks = locks;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ authorize

    public record AuthorizeResult(Acquisition acquisition, boolean replay) {}

    /**
     * Authorizes one acquiring sale at the rail. Idempotent on the {@code Idempotency-Key}
     * (UNIQUE on {@code acquisition.idempotency_key}) and on (merchant, pspRef).
     */
    public AuthorizeResult authorize(UUID tenantId, UUID merchantId, String connectorCode,
                                     String pspRef, long grossMinor, long feeMinor,
                                     String currency, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_IDEMPOTENCY_REQUIRED",
                "acquire requires an Idempotency-Key header (1..128 chars)");
        }
        String ccy = (currency == null || currency.isBlank()) ? "" : currency.strip().toUpperCase();
        if (!props.getAcquire().getSupportedCurrencies().contains(ccy)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PSP_CURRENCY_UNSUPPORTED",
                "currency not supported on Phase-0 rails: " + ccy);
        }
        validateAmounts(grossMinor, feeMinor);

        var replay = acquisitions.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            Acquisition a = replay.get();
            requireSameRequest(a, tenantId, merchantId, pspRef, grossMinor, feeMinor, ccy);
            return new AuthorizeResult(a, true);
        }

        Merchant merchant = merchants.byId(tenantId, merchantId);
        MerchantService.requireAcquirable(merchant);
        BankConnector connector = connectors.enabledByCode(connectorCode);

        return locks.withLock("acq-key", idempotencyKey, () -> {
            var again = acquisitions.findByIdempotencyKey(idempotencyKey);
            if (again.isPresent()) {
                Acquisition a = again.get();
                requireSameRequest(a, tenantId, merchantId, pspRef, grossMinor, feeMinor, ccy);
                return new AuthorizeResult(a, true);
            }
            var byRef = acquisitions.findByMerchantIdAndPspRef(merchantId, pspRef);
            if (byRef.isPresent()) {
                throw new ApiException(HttpStatus.CONFLICT, "PSP_REF_TAKEN",
                    "pspRef already used by acquisition " + byRef.get().id);
            }

            Acquisition a = new Acquisition();
            a.id = UUID.randomUUID();
            a.merchantId = merchantId;
            a.tenantId = tenantId;
            a.connectorCode = connectorCode;
            a.currency = ccy;
            a.grossMinor = BigDecimal.valueOf(grossMinor);
            a.feeMinor = BigDecimal.valueOf(feeMinor);
            a.netMinor = BigDecimal.valueOf(grossMinor - feeMinor);
            a.pspRef = pspRef;
            a.state = Acquisition.STATE_CREATED;
            a.idempotencyKey = idempotencyKey;
            a.createdAt = Instant.now();
            a.updatedAt = a.createdAt;
            // Audit first: the row is the saga of record; the rail call follows outside TX.
            tx.execute(s -> {
                acquisitions.save(a);
                appendEvent(a, "ACQUISITION_CREATED", null, Acquisition.STATE_CREATED, null);
                emitOutbox(a.id, "ACQUISITION_CREATED", a);
                return a;
            });

            sendToRail(a, merchant, connector);
            return new AuthorizeResult(reload(a), false);
        });
    }

    /**
     * Rail submission + disposition handling. Retried by the TTL sweeper for CREATED sagas
     * with the same deterministic ids (exactly-once via {@code rail_call}).
     */
    void sendToRail(Acquisition a, Merchant merchant, BankConnector connector) {
        String callKey = "RAIL_AUTHORIZE:" + a.id + ":" + a.idempotencyKey;
        RailCall call = recordCallStart(callKey, connector.bankCode, a.merchantId);
        if (RailCall.STATE_ACKED.equals(call.state) && a.railRef != null) {
            // Already decided exactly-once — just ensure the row state caught up.
            markAuthorized(a, call.railRef);
            return;
        }

        AcquirerGateway gateway = connector.messageFormat != null
            && connector.messageFormat.equalsIgnoreCase("DEMO") ? demoGateway : restGateway;

        RailResult result;
        try {
            result = gateway.authorize(connector, new AcquirerGateway.AcquireRequest(
                a.pspRef, a.gross(), a.currency, merchant.merchantCode));
        } catch (RailTransportException transport) {
            // Outcome UNKNOWN. Nothing advanced; sweeper replays with the same ids.
            finishCall(call, RailCall.STATE_FAILED, null,
                transport.kind().getClass().getSimpleName(), null);
            log.warn("psp_rail_authorize_unknown outcome=UNKNOWN acq={} kind={}",
                a.id, transport.kind().getClass().getSimpleName());
            return;
        }

        switch (result) {
            case RailResult.Accepted accepted -> {
                finishCall(call, RailCall.STATE_ACKED, accepted.railRef(), null, null);
                markAuthorized(a, accepted.railRef());
            }
            case RailResult.AcceptedPending pending -> {
                // Reserved at the rail; capture may proceed (funds are already held).
                finishCall(call, RailCall.STATE_ACKED, pending.railRef(), null, null);
                markAuthorized(a, pending.railRef());
            }
            case RailResult.Rejected rejected -> {
                finishCall(call, RailCall.STATE_REJECTED, null, null, rejected.code());
                reject(a, rejected.code());
            }
            case RailResult.Failed failed -> {
                if (failed.kind() instanceof RailResult.FailureKind.DefinitiveFailure def) {
                    finishCall(call, RailCall.STATE_REJECTED, null, "DefinitiveFailure", def.railCode());
                    reject(a, def.railCode());
                } else {
                    // Local backpressure / throttle / unavailable: nothing was sent;
                    // the TTL sweeper retries with the same ids.
                    finishCall(call, RailCall.STATE_FAILED, null,
                        failed.kind().getClass().getSimpleName(), null);
                    log.info("psp_rail_authorize_deferred acq={} kind={}",
                        a.id, failed.kind().getClass().getSimpleName());
                }
            }
        }
    }

    private void markAuthorized(Acquisition a, String railRef) {
        tx.execute(s -> {
            a.railRef = railRef;
            a.state = Acquisition.STATE_AUTHORIZED;
            a.updatedAt = Instant.now();
            acquisitions.save(a);
            appendEvent(a, "ACQUISITION_AUTHORIZED", Acquisition.STATE_CREATED,
                Acquisition.STATE_AUTHORIZED, null);
            emitOutbox(a.id, "ACQUISITION_AUTHORIZED", a);
            return a;
        });
    }

    private void reject(Acquisition a, String code) {
        tx.execute(s -> {
            a.state = Acquisition.STATE_REJECTED;
            a.failureCode = code;
            a.updatedAt = Instant.now();
            acquisitions.save(a);
            appendEvent(a, "ACQUISITION_REJECTED", Acquisition.STATE_CREATED,
                Acquisition.STATE_REJECTED, code);
            emitOutbox(a.id, "ACQUISITION_REJECTED", a);
            return a;
        });
    }

    // ------------------------------------------------------------------ capture

    /**
     * Books the two-phase pending chain (float → clearing, fee extraction) and flips the
     * saga to CAPTURED with its settlement cycle date. Idempotent: CAPTURED/SETTLED replay
     * is a no-op; TB replays re-send the SAME pending ids (Exists = success).
     */
    public Acquisition capture(UUID tenantId, UUID acquisitionId) {
        return locks.withLock("acquisition", acquisitionId, () -> {
            Acquisition a = owned(tenantId, acquisitionId);
            switch (a.state) {
                case Acquisition.STATE_CAPTURED, Acquisition.STATE_SETTLED -> { return reload(a); }
                case Acquisition.STATE_AUTHORIZED -> { /* fall through */ }
                default -> throw new ApiException(HttpStatus.CONFLICT, "PSP_ACQ_STATE",
                    "cannot capture acquisition in state " + a.state);
            }

            Merchant merchant = merchants.byId(tenantId, a.merchantId);
            MerchantService.requireAcquirable(merchant);

            ensureBookAccounts(a);
            PspTbResult capture = createCaptureLegs(a);
            switch (capture) {
                case PspTbResult.Ok ok -> { /* legs booked → commit state below */ }
                case PspTbResult.DefinitiveRejection rejected -> {
                    // TB refused the chain (e.g. bound violation): no money moved.
                    log.warn("psp_capture_rejected acq={} detail={}", a.id, rejected.detail());
                    throw new ApiException(HttpStatus.CONFLICT, "PSP_TB_REJECTED",
                        "capture rejected by ledger; funds unchanged");
                }
                case PspTbResult.UnknownTransport unknown -> {
                    log.warn("psp_capture_unknown_transport acq={}", a.id);
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PSP_TB_UNKNOWN",
                        "capture outcome unknown; retry idempotently");
                }
            }

            LocalDate cycle = LocalDate.now(ZoneOffset.UTC);
            return tx.execute(s -> {
                a.state = Acquisition.STATE_CAPTURED;
                a.cycleDate = cycle;
                a.updatedAt = Instant.now();
                acquisitions.save(a);
                // Bookkeeping mirror of the TB pending gross: the clearing position row
                // is what the settlement sweep decrements (zero-clearing invariant).
                ClearingPosition position = positions
                    .findByConnectorCodeAndCurrencyAndPositionDate(
                        a.connectorCode, a.currency, cycle)
                    .orElseGet(() -> {
                        ClearingPosition p = new ClearingPosition();
                        p.id = UUID.randomUUID();
                        p.connectorCode = a.connectorCode;
                        p.currency = a.currency;
                        p.positionDate = cycle;
                        p.tbAccountId = PspUuid5.clearingAccountId(a.connectorCode, a.currency);
                        p.amountMinor = BigDecimal.ZERO;
                        p.createdAt = Instant.now();
                        return p;
                    });
                position.amountMinor = position.amountMinor
                    .add(BigDecimal.valueOf(a.gross()));
                position.updatedAt = Instant.now();
                positions.save(position);
                appendEvent(a, "ACQUISITION_CAPTURED", Acquisition.STATE_AUTHORIZED,
                    Acquisition.STATE_CAPTURED, null);
                emitOutbox(a.id, "ACQUISITION_CAPTURED", a);
                return a;
            });
        });
    }

    /**
     * Voids a not-yet-settled acquisition. CREATED/REJECTED: no money moved, direct state
     * flip. CAPTURED: void-pending transfers release the pending chain (pre-sweep only);
     * post-sweep the money is owed to the merchant (settlement/dispute flow), so void is
     * refused with PSP_ACQ_SETTLED-path semantics (409).
     */
    public Acquisition voidAcquisition(UUID tenantId, UUID acquisitionId) {
        return locks.withLock("acquisition", acquisitionId, () -> {
            Acquisition a = owned(tenantId, acquisitionId);
            switch (a.state) {
                case Acquisition.STATE_VOIDED -> { return reload(a); }
                case Acquisition.STATE_CREATED, Acquisition.STATE_REJECTED -> {
                    return tx.execute(s -> {
                        a.state = Acquisition.STATE_VOIDED;
                        a.updatedAt = Instant.now();
                        acquisitions.save(a);
                        appendEvent(a, "ACQUISITION_VOIDED", a.state,
                            Acquisition.STATE_VOIDED, null);
                        emitOutbox(a.id, "ACQUISITION_VOIDED", a);
                        return a;
                    });
                }
                case Acquisition.STATE_CAPTURED -> { /* void the pending chain below */ }
                default -> throw new ApiException(HttpStatus.CONFLICT, "PSP_ACQ_COMMITTED",
                    "settled acquisitions cannot be voided; use the dispute/reversal flow");
            }

            ensureBookAccounts(a);
            PspTbResult voided = createVoidLegs(a);
            switch (voided) {
                case PspTbResult.Ok ok -> { /* chain released → commit state below */ }
                case PspTbResult.DefinitiveRejection rejected -> {
                    log.warn("psp_void_rejected acq={} detail={}", a.id, rejected.detail());
                    throw new ApiException(HttpStatus.CONFLICT, "PSP_TB_REJECTED",
                        "void rejected by ledger");
                }
                case PspTbResult.UnknownTransport unknown -> {
                    log.warn("psp_void_unknown_transport acq={}", a.id);
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PSP_TB_UNKNOWN",
                        "void outcome unknown; retry idempotently");
                }
            }
            return tx.execute(s -> {
                a.state = Acquisition.STATE_VOIDED;
                a.updatedAt = Instant.now();
                acquisitions.save(a);
                appendEvent(a, "ACQUISITION_VOIDED", Acquisition.STATE_CAPTURED,
                    Acquisition.STATE_VOIDED, null);
                emitOutbox(a.id, "ACQUISITION_VOIDED", a);
                return a;
            });
        });
    }

    // ------------------------------------------------------------------ reads

    public Acquisition owned(UUID tenantId, UUID acquisitionId) {
        Acquisition a = acquisitions.findByIdAndTenantId(acquisitionId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PSP_ACQ_NOT_FOUND",
                "acquisition not found"));
        return a;
    }

    /** Merchant-scoped history — server-side pagination/sorting/search (platform §2f). */
    public Page<Acquisition> history(UUID tenantId, UUID merchantId, int page, int size,
                                     String sort, String state, String q) {
        merchants.byId(tenantId, merchantId); // tenant scoping (404 otherwise)
        String stateFilter = (state == null || state.isBlank()) ? null : state.strip();
        Pageable pageable = pageOf(page, size, sort);
        return acquisitions.search(merchantId, stateFilter,
            (q == null || q.isBlank()) ? null : q.strip(), pageable);
    }

    // ------------------------------------------------------------------ TB legs

    /** Capture chain (EMI doctrine): N legs all PENDING; all but the LAST carry LINKED.
     *  leg0 DR float gross → CR clearing gross; leg1 (last) DR clearing fee → CR fee-revenue. */
    private PspTbResult createCaptureLegs(Acquisition a) {
        List<PspTbService.TransferSpec> specs = new ArrayList<>(2);
        UUID pending0 = PspUuid5.captureLegTransferId(a.id, 0);
        UUID pending1 = PspUuid5.captureLegTransferId(a.id, 1);
        boolean hasFee = a.fee() > 0;

        specs.add(new PspTbService.TransferSpec(pending0,
            PspUuid5.settlementFloatAccountId(a.currency),
            PspUuid5.clearingAccountId(a.connectorCode, a.currency),
            a.gross(), tb.bookId(), CODE_CAPTURE,
            PspTbService.FLAG_PENDING | (hasFee ? PspTbService.FLAG_LINKED : 0),
            a.id, 0L, 0, null,
            (int) props.getSettlement().getSettleGraceSeconds()));
        if (hasFee) {
            specs.add(new PspTbService.TransferSpec(pending1,
                PspUuid5.clearingAccountId(a.connectorCode, a.currency),
                PspUuid5.feeRevenueAccountId(a.currency),
                a.fee(), tb.bookId(), CODE_FEE_EXTRACTION,
                PspTbService.FLAG_PENDING, a.id, 0L, 0, null, 0));
        }
        return tb.createTransfers(specs);
    }

    /** Post-pending commits of the capture chain — same linked layout (atomic resolution). */
    public List<PspTbService.TransferSpec> commitLegSpecs(Acquisition a) {
        List<PspTbService.TransferSpec> specs = new ArrayList<>(2);
        boolean hasFee = a.fee() > 0;
        specs.add(new PspTbService.TransferSpec(PspUuid5.commitLegTransferId(a.id, 0),
            PspUuid5.settlementFloatAccountId(a.currency),
            PspUuid5.clearingAccountId(a.connectorCode, a.currency),
            a.gross(), tb.bookId(), CODE_CAPTURE,
            PspTbService.FLAG_POST_PENDING | (hasFee ? PspTbService.FLAG_LINKED : 0),
            a.id, 0L, 0,
            PspUuid5.captureLegTransferId(a.id, 0), 0));
        if (hasFee) {
            specs.add(new PspTbService.TransferSpec(PspUuid5.commitLegTransferId(a.id, 1),
                PspUuid5.clearingAccountId(a.connectorCode, a.currency),
                PspUuid5.feeRevenueAccountId(a.currency),
                a.fee(), tb.bookId(), CODE_FEE_EXTRACTION,
                PspTbService.FLAG_POST_PENDING, a.id, 0L, 0,
                PspUuid5.captureLegTransferId(a.id, 1), 0));
        }
        return specs;
    }

    /** Void-pending mirrors of the capture chain (only legal pre-sweep). */
    private PspTbResult createVoidLegs(Acquisition a) {
        List<PspTbService.TransferSpec> specs = new ArrayList<>(2);
        boolean hasFee = a.fee() > 0;
        specs.add(new PspTbService.TransferSpec(PspUuid5.voidLegTransferId(a.id, 0),
            PspUuid5.settlementFloatAccountId(a.currency),
            PspUuid5.clearingAccountId(a.connectorCode, a.currency),
            a.gross(), tb.bookId(), CODE_CAPTURE,
            PspTbService.FLAG_VOID_PENDING | (hasFee ? PspTbService.FLAG_LINKED : 0),
            a.id, 0L, 0,
            PspUuid5.captureLegTransferId(a.id, 0), 0));
        if (hasFee) {
            specs.add(new PspTbService.TransferSpec(PspUuid5.voidLegTransferId(a.id, 1),
                PspUuid5.clearingAccountId(a.connectorCode, a.currency),
                PspUuid5.feeRevenueAccountId(a.currency),
                a.fee(), tb.bookId(), CODE_FEE_EXTRACTION,
                PspTbService.FLAG_VOID_PENDING, a.id, 0L, 0,
                PspUuid5.captureLegTransferId(a.id, 1), 0));
        }
        return tb.createTransfers(specs);
    }

    /**
     * Creates the fixed account set for one connector+currency flow on the PSP book:
     * settlement-float (asset), clearing (nominal, zero per cycle), fee-revenue (nominal),
     * plus the merchant payable (liability). Existence is idempotent (Exists = success).
     */
    public void ensureBookAccounts(Acquisition a) {
        UUID merchantId = a.merchantId;
        List<PspTbService.AccountSpec> specs = List.of(
            new PspTbService.AccountSpec(PspUuid5.settlementFloatAccountId(a.currency),
                tb.bookId(), CODE_ASSET_SETTLEMENT_FLOAT,
                tb.flagFor(PspTbService.Bound.DEBIT_BOUND, false), null, 0L, 0),
            new PspTbService.AccountSpec(PspUuid5.clearingAccountId(a.connectorCode, a.currency),
                tb.bookId(), CODE_NOMINAL_CLEARING,
                tb.flagFor(PspTbService.Bound.NONE, true), null, 0L, 0),
            new PspTbService.AccountSpec(PspUuid5.feeRevenueAccountId(a.currency),
                tb.bookId(), CODE_REVENUE_FEE,
                tb.flagFor(PspTbService.Bound.NONE, true), null, 0L, 0),
            new PspTbService.AccountSpec(PspUuid5.merchantPayableAccountId(merchantId, a.currency),
                tb.bookId(), CODE_LIABILITY_MERCHANT_PAYABLE,
                tb.flagFor(PspTbService.Bound.CREDIT_BOUND, true), null, 0L, 0));
        PspTbResult r = tb.createAccounts(specs);
        if (r instanceof PspTbResult.DefinitiveRejection rejected) {
            throw new ApiException(HttpStatus.CONFLICT, "PSP_TB_REJECTED",
                "book accounts rejected: " + rejected.detail());
        }
        if (r instanceof PspTbResult.UnknownTransport) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PSP_TB_UNKNOWN",
                "account provisioning outcome unknown; retry idempotently");
        }
    }

    // ------------------------------------------------------------------ helpers


    private void validateAmounts(long grossMinor, long feeMinor) {
        if (grossMinor <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_AMOUNT_INVALID",
                "grossMinor must be positive");
        }
        if (feeMinor < 0 || feeMinor > grossMinor) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_FEE_INVALID",
                "feeMinor must be within 0..grossMinor");
        }
        if (grossMinor > props.getAcquire().getMaxMovementMinor()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_AMOUNT_CAP",
                "amount exceeds movement cap");
        }
    }

    private void requireSameRequest(Acquisition a, UUID tenantId, UUID merchantId, String pspRef,
                                    long grossMinor, long feeMinor, String currency) {
        if (!a.tenantId.equals(tenantId) || !a.merchantId.equals(merchantId)
                || !a.pspRef.equals(pspRef) || a.gross() != grossMinor || a.fee() != feeMinor
                || !a.currency.equals(currency)) {
            throw new ApiException(HttpStatus.CONFLICT, "PSP_IDEMPOTENCY_DRIFT",
                "idempotency key reused with a different request");
        }
    }

    private RailCall recordCallStart(String callKey, String connectorCode, UUID merchantId) {
        var existing = calls.findByCallKey(callKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        RailCall c = new RailCall();
        c.id = UUID.randomUUID();
        c.kind = RailCall.KIND_RAIL_AUTHORIZE;
        c.callKey = callKey;
        c.connectorCode = connectorCode;
        c.merchantId = merchantId;
        c.state = RailCall.STATE_RECORDED;
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        return tx.execute(s -> calls.save(c));
    }

    private void finishCall(RailCall call, String state, String railRef,
                            String failureKind, String failureCode) {
        tx.execute(s -> {
            call.state = state;
            if (railRef != null) call.railRef = railRef;
            call.failureKind = failureKind;
            call.failureCode = failureCode;
            call.updatedAt = Instant.now();
            return calls.save(call);
        });
    }

    private void appendEvent(Acquisition a, String kind, String from, String to, String detail) {
        PspEvent e = new PspEvent();
        e.acquisitionId = a.id;
        e.merchantId = a.merchantId;
        e.kind = kind;
        e.fromState = from;
        e.toState = to;
        e.detail = detail;
        e.createdAt = Instant.now();
        events.save(e);
    }

    private void emitOutbox(UUID aggregateId, String kind, Acquisition a) {
        PspOutbox o = new PspOutbox();
        o.id = UUID.randomUUID();
        o.aggregateId = aggregateId;
        o.kind = kind;
        o.payloadJson = """
            {"acquisitionId":"%s","merchantId":"%s","state":"%s","grossMinor":"%d","netMinor":"%d"}
            """.formatted(a.id, a.merchantId, a.state, a.gross(), a.net());
        o.createdAt = Instant.now();
        outbox.save(o);
    }

    private Acquisition reload(Acquisition a) {
        return acquisitions.findById(a.id).orElse(a);
    }

    public static Pageable pageOf(int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_PAGE_INVALID",
                "page must be ≥0 and size in 1..100");
        }
        String field = "createdAt";
        org.springframework.data.domain.Sort.Direction direction =
            org.springframework.data.domain.Sort.Direction.DESC;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",", 2);
            if (!SORTABLE.contains(parts[0])) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_SORT_INVALID",
                    "sort field not permitted");
            }
            field = parts[0];
            if (parts.length == 2 && "asc".equalsIgnoreCase(parts[1].strip())) {
                direction = org.springframework.data.domain.Sort.Direction.ASC;
            } else if (parts.length == 2 && "desc".equalsIgnoreCase(parts[1].strip())) {
                direction = org.springframework.data.domain.Sort.Direction.DESC;
            } else if (parts.length == 2) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PSP_SORT_INVALID",
                    "sort direction must be asc|desc");
            }
        }
        return org.springframework.data.domain.PageRequest.of(page, size,
            org.springframework.data.domain.Sort.by(direction, field));
    }

    // TB account/transfer codes (PSP book conventions, documented before change).
    public static final int CODE_ASSET_SETTLEMENT_FLOAT = 401;
    public static final int CODE_NOMINAL_CLEARING = 402;
    public static final int CODE_LIABILITY_MERCHANT_PAYABLE = 403;
    public static final int CODE_REVENUE_FEE = 404;
    public static final int CODE_CAPTURE = 501;
    public static final int CODE_FEE_EXTRACTION = 502;
    public static final int CODE_CLOSING_DEBIT = 503;
}
