package com.fsp.emi.charge;

import com.fsp.emi.common.ApiException;
import com.fsp.emi.common.KeyLocks;
import com.fsp.emi.common.Uuid5;
import com.fsp.emi.config.EmiProperties;
import com.fsp.emi.ledger.EmiTbResult;
import com.fsp.emi.ledger.EmiTbService;
import com.fsp.emi.wallet.Wallet;
import com.fsp.emi.wallet.WalletRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Stage 4 — SKU money (plan §7.1): the wallet-funded order charge.
 *
 * A charge is a LINKED PENDING chain of two-phase transfers, DR wallet → CR derived
 * clearing accounts (sku-clearing / fee-clearing / tax-liability). The linked flag makes
 * the whole chain atomic: either every leg holds, or none does — a partial failure can
 * never strand value between buckets (R6). Confirm (fulfilment precondition flipped in
 * core) posts the resolving post-pending chain and commits all legs as one; void releases
 * all legs; TB auto-expiry at the saga grace timeout marks the charge EXPIRED ("timeout is
 * not failure" — the hold reverts to the wallet, R2/R3).
 *
 * Idempotency (§2e): UNIQUE (wallet_id, order_ref) anchors the charge; deterministic Uuid5
 * leg ids make replays byte-identical at TB (exists ⇒ success). The price fingerprint is
 * re-hashed here — a charge is accepted only when the quoted breakdown hashes to what core
 * snapshotted, so a repo price change between quote and charge is visible, never silent (R1).
 *
 * Post-commit void is refused (EMI_CHARGE_COMMITTED): confirmed charges resolve through
 * the dispute flow (reversal classes), never an inline void — the bill-saga doctrine,
 * now enforced for orders too.
 */
@Slf4j
@Service
public class ChargeService {

    static final int ACCOUNT_CODE_SKU_CLEARING = 305;
    static final int ACCOUNT_CODE_FEE_CLEARING = 306;
    static final int ACCOUNT_CODE_TAX_LIABILITY = 307;
    static final int JE_CODE_MOVEMENT = 1;

    private final WalletRepository wallets;
    private final ChargeRepository charges;
    private final EmiTbService tb;
    private final EmiProperties props;
    private final KeyLocks locks;
    private final TransactionTemplate tx;

    public ChargeService(WalletRepository wallets, ChargeRepository charges, EmiTbService tb,
                         EmiProperties props, KeyLocks locks, TransactionTemplate tx) {
        this.wallets = wallets;
        this.charges = charges;
        this.tb = tb;
        this.props = props;
        this.locks = locks;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ fingerprint

    /**
     * Canonical fingerprint over the quoted breakdown + order reference. Both core (at
     * quote time) and EMI (at charge time) hash the exact same string.
     */
    public static String fingerprint(UUID walletId, String orderRef, long merchMinor,
                                     long feeMinor, long taxMinor, String currency) {
        String basis = "sku|%s|%s|%d|%d|%d|%s".formatted(walletId, orderRef,
            merchMinor, feeMinor, taxMinor, currency);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(basis.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ charge (hold)

    /** Places the linked pending chain. Idempotent on (walletId, orderRef). */
    public Charge charge(UUID walletId, UUID actorPersonId, String orderRef,
                         long merchMinor, long feeMinor, long taxMinor,
                         String currency, String fingerprint) {
        validOrderRef(orderRef);
        if (merchMinor < 0 || feeMinor < 0 || taxMinor < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_CHARGE_NEGATIVE",
                "charge amounts must be non-negative");
        }
        long total = merchMinor + feeMinor + taxMinor;
        if (total <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_CHARGE_ZERO",
                "charge total must be positive");
        }
        if (!"NAD".equals(currency)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EMI_CURRENCY_UNSUPPORTED",
                "regulated wallet charges are NAD-only (PSD-3)");
        }
        String expected = fingerprint(walletId, orderRef, merchMinor, feeMinor, taxMinor, currency);
        if (fingerprint == null || !expected.equalsIgnoreCase(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMI_PRICE_FINGERPRINT_MISMATCH",
                "quoted breakdown does not hash to the core price snapshot");
        }

        return locks.withLock("charge", walletId, () -> {
            // idempotency first: a replayed order returns the original charge untouched
            Charge replayed = charges.findByWalletIdAndOrderRef(walletId, orderRef).orElse(null);
            if (replayed != null) {
                requireOwner(replayed, actorPersonId);
                return replayed;
            }

            Wallet wallet = walletOr404(walletId);
            requireOwner(wallet, actorPersonId);
            validateActive(wallet);

            Charge charge = new Charge();
            charge.id = UUID.randomUUID();
            charge.walletId = walletId;
            charge.personId = wallet.personId;
            charge.orderRef = orderRef;
            charge.currency = currency;
            charge.merchMinor = BigDecimal.valueOf(merchMinor);
            charge.feeMinor = BigDecimal.valueOf(feeMinor);
            charge.taxMinor = BigDecimal.valueOf(taxMinor);
            charge.totalMinor = BigDecimal.valueOf(total);
            charge.priceFingerprint = expected;
            charge.state = Charge.State.HOLD;
            charge.createdAt = Instant.now();
            charge.updatedAt = charge.createdAt;
            tx.execute(s -> charges.save(charge));

            return executeLegs(charge, LegPhase.HOLD);
        });
    }

    // ------------------------------------------------------------------ lifecycle

    /** Fulfilment confirmed: the resolving post-pending chain commits all legs atomically. */
    public Charge confirmCharge(UUID chargeId, UUID actorPersonId) {
        return locks.withLock("charge", chargeId, () -> {
            Charge charge = chargeOr404(chargeId);
            requireOwner(charge, actorPersonId);
            if (Charge.State.CONFIRMED.equals(charge.state)) {
                return charge; // idempotent replay
            }
            if (!Charge.State.HOLD.equals(charge.state)) {
                throw new ApiException(HttpStatus.CONFLICT, "EMI_CHARGE_STATE",
                    "charge is " + charge.state + "; confirm applies only to HOLD");
            }
            return executeLegs(charge, LegPhase.COMMIT);
        });
    }

    /** Pre-fulfilment cancel: the resolving void-pending chain releases all legs atomically. */
    public Charge voidCharge(UUID chargeId, UUID actorPersonId) {
        return locks.withLock("charge", chargeId, () -> {
            Charge charge = chargeOr404(chargeId);
            requireOwner(charge, actorPersonId);
            if (Charge.State.VOIDED.equals(charge.state) || Charge.State.EXPIRED.equals(charge.state)) {
                return charge; // idempotent replay
            }
            if (Charge.State.CONFIRMED.equals(charge.state)) {
                throw new ApiException(HttpStatus.CONFLICT, "EMI_CHARGE_COMMITTED",
                    "confirmed charges resolve through the dispute flow, not inline void");
            }
            return executeLegs(charge, LegPhase.VOID);
        });
    }

    /**
     * Backstop for holds nobody confirmed or voided within the grace window: attempt a
     * release; if TB expired the pendings first, the charge repairs to EXPIRED. Bounded
     * paging (§2f), fair striped locks per charge, never inside a DB transaction (§2d).
     */
    public int sweepExpired() {
        Instant cutoff = Instant.now().minusSeconds(props.getSagaGraceSeconds());
        int handled = 0;
        int page = 0;
        for (;;) {
            var batch = charges.search(null, Charge.State.HOLD, null,
                PageRequest.of(page, 100, Sort.by(Sort.Direction.ASC, "createdAt")));
            if (batch.isEmpty()) break;
            for (Charge c : batch.getContent()) {
                if (c.createdAt.isAfter(cutoff)) continue; // only holds past their grace window
                try {
                    Charge after = locks.withLock("charge", c.id, () -> {
                        Charge fresh = chargeOr404(c.id);
                        return Charge.State.HOLD.equals(fresh.state)
                            ? executeLegs(fresh, LegPhase.VOID) : fresh;
                    });
                    if (!Charge.State.HOLD.equals(after.state)) handled++;
                } catch (ApiException e) {
                    log.warn("charge_sweep_release_failed charge={} code={}", c.id, e.getCode());
                }
            }
            if (!batch.hasNext()) break;
            page++;
        }
        if (handled > 0) log.info("charge_sweep_handled count={}", handled);
        return handled;
    }

    // ------------------------------------------------------------------ TB legs

    private enum LegPhase { HOLD, COMMIT, VOID }

    /** One TB call per charge: the linked (or single) chain of hold or resolution legs. */
    private Charge executeLegs(Charge charge, LegPhase phase) {
        long[] amounts = legAmounts(charge);
        UUID[] credits = legCredits(charge, amounts);
        List<EmiTbService.TransferSpec> specs = new ArrayList<>(amounts.length);
        for (int i = 0; i < amounts.length; i++) {
            boolean linked = amounts.length > 1 && i < amounts.length - 1;
            int flags = switch (phase) {
                case HOLD -> EmiTbService.FLAG_PENDING;
                case COMMIT -> EmiTbService.FLAG_POST_PENDING;
                case VOID -> EmiTbService.FLAG_VOID_PENDING;
            } | (linked ? EmiTbService.FLAG_LINKED : 0);
            specs.add(switch (phase) {
                case HOLD -> new EmiTbService.TransferSpec(
                    Uuid5.chargeLegTransferId(charge.id, i),
                    walletAccount(charge), credits[i], amounts[i], tb.bookId(), JE_CODE_MOVEMENT,
                    flags, charge.id, 0, 0, null, holdTimeoutSeconds());
                default -> new EmiTbService.TransferSpec(
                    (phase == LegPhase.VOID ? Uuid5.chargeVoidTransferId(charge.id, i)
                                            : Uuid5.chargeCommitTransferId(charge.id, i)),
                    walletAccount(charge), credits[i], amounts[i], tb.bookId(), JE_CODE_MOVEMENT,
                    flags, charge.id, 0, 0,
                    Uuid5.chargeLegTransferId(charge.id, i), 0);
            });
        }

        if (phase == LegPhase.HOLD) {
            EmiTbResult accounts = ensureDerivedAccounts(charge);
            if (!(accounts instanceof EmiTbResult.Ok)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMI_TB_UNKNOWN",
                    "derived clearing accounts unreachable; hold not placed");
            }
        }

        var result = tb.createTransfers(specs);
        switch (result) {
            case EmiTbResult.Ok ok -> {
                charge.state = switch (phase) {
                    case HOLD -> Charge.State.HOLD; // row already persisted as HOLD
                    case COMMIT -> Charge.State.CONFIRMED;
                    case VOID -> Charge.State.VOIDED;
                };
                charge.updatedAt = Instant.now();
                return tx.execute(s -> charges.save(charge));
            }
            case EmiTbResult.DefinitiveRejection rejected -> {
                String detail = afterPrefix(rejected.detail());
                if (detail.contains("PendingTransferExpired")) {
                    // TB expired the pending chain first — DB truth repairs to match TB
                    charge.state = Charge.State.EXPIRED;
                    charge.updatedAt = Instant.now();
                    return tx.execute(s -> charges.save(charge));
                }
                if (phase == LegPhase.HOLD) {
                    // definitive leg rejection (bounds/data): no hold exists; terminal row first
                    charge.state = Charge.State.VOIDED;
                    charge.updatedAt = Instant.now();
                    tx.execute(s -> charges.save(charge));
                    throw new ApiException(HttpStatus.CONFLICT, "EMI_TB_REJECTED", detail);
                }
                throw new ApiException(HttpStatus.CONFLICT, "EMI_TB_REJECTED", detail);
            }
            case EmiTbResult.UnknownTransport unknown -> {
                // stay HOLD regardless of phase; replays carry the same deterministic ids (§2d)
                charge.updatedAt = Instant.now();
                tx.execute(s -> charges.save(charge));
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMI_TB_UNKNOWN",
                    "TB outcome unknown; retry carries the same deterministic leg ids");
            }
            default -> throw new IllegalStateException("sealed result exhausted");
        }
    }

    /** Non-zero leg amounts in fixed chain order [merch, fee, tax]. */
    private long[] legAmounts(Charge charge) {
        List<Long> legs = new ArrayList<>(3);
        if (charge.merchMinor.signum() > 0) legs.add(charge.merchMinor.longValueExact());
        if (charge.feeMinor.signum() > 0) legs.add(charge.feeMinor.longValueExact());
        if (charge.taxMinor.signum() > 0) legs.add(charge.taxMinor.longValueExact());
        return legs.stream().mapToLong(Long::longValue).toArray();
    }

    /** Per-leg credit targets, same fixed order: sku-clearing, fee-clearing, tax-liability. */
    private UUID[] legCredits(Charge charge, long[] amounts) {
        List<UUID> credits = new ArrayList<>(3);
        if (charge.merchMinor.signum() > 0) credits.add(Uuid5.skuClearingAccountId(charge.currency));
        if (charge.feeMinor.signum() > 0) credits.add(Uuid5.feeClearingAccountId(charge.currency));
        if (charge.taxMinor.signum() > 0) credits.add(Uuid5.taxLiabilityAccountId(charge.currency));
        return credits.toArray(UUID[]::new);
    }

    private List<EmiTbService.AccountSpec> derivedAccountSpecs(Charge charge) {
        List<EmiTbService.AccountSpec> specs = new ArrayList<>(3);
        if (charge.merchMinor.signum() > 0) {
            specs.add(acct(ACCOUNT_CODE_SKU_CLEARING, Uuid5.skuClearingAccountId(charge.currency)));
        }
        if (charge.feeMinor.signum() > 0) {
            specs.add(acct(ACCOUNT_CODE_FEE_CLEARING, Uuid5.feeClearingAccountId(charge.currency)));
        }
        if (charge.taxMinor.signum() > 0) {
            specs.add(acct(ACCOUNT_CODE_TAX_LIABILITY, Uuid5.taxLiabilityAccountId(charge.currency)));
        }
        return specs;
    }

    /** Derived clearing accounts: no bounds, history flag on (mirrors bill-clearing). */
    private EmiTbResult ensureDerivedAccounts(Charge charge) {
        return tb.createAccounts(derivedAccountSpecs(charge));
    }

    private EmiTbService.AccountSpec acct(int code, UUID id) {
        return new EmiTbService.AccountSpec(id, tb.bookId(), code,
            tb.flagFor(EmiTbService.Bound.NONE, true), null, 0, 0);
    }

    private int holdTimeoutSeconds() {
        return (int) Math.min(props.getSagaGraceSeconds(), Integer.MAX_VALUE);
    }

    private UUID walletAccount(Charge charge) {
        return Uuid5.walletAccountId(charge.walletId);
    }

    // ------------------------------------------------------------------ guards & helpers

    /** CreateTransferStatus list prefix ("createTransfers rejected: [...]") is engine noise. */
    private static String afterPrefix(String detail) {
        int idx = detail.indexOf(": ");
        return idx >= 0 ? detail.substring(idx + 2) : detail;
    }

    private static void validOrderRef(String s) {
        if (s == null || s.isBlank() || !s.matches("[A-Za-z0-9_.\\-:/]{1,128}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_ORDER_REF_INVALID",
                "orderRef must match [A-Za-z0-9_.\\-:/]{1,128}");
        }
    }

    private void requireOwner(Object o, UUID actorPersonId) {
        UUID owner = o instanceof Charge c ? c.personId
            : o instanceof Wallet w ? w.personId : null;
        if (owner == null || !owner.equals(actorPersonId)) {
            throw notFound(); // 404 — never leak existence across owners
        }
    }

    private Wallet walletOr404(UUID walletId) {
        return wallets.findById(walletId).orElseThrow(this::notFound);
    }

    private Charge chargeOr404(UUID chargeId) {
        return charges.findById(chargeId).orElseThrow(this::notFound);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "EMI_NOT_FOUND",
            "resource does not exist for this actor");
    }

    private void validateActive(Wallet w) {
        if (!Wallet.Status.ACTIVE.equals(w.status)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMI_WALLET_NOT_ACTIVE",
                "wallet is " + w.status);
        }
    }
}
