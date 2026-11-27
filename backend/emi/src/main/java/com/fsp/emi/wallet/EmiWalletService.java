package com.fsp.emi.wallet;

import com.fsp.emi.common.ApiException;
import com.fsp.emi.common.KeyLocks;
import com.fsp.emi.common.Uuid5;
import com.fsp.emi.config.EmiProperties;
import com.fsp.emi.ledger.EmiTbResult;
import com.fsp.emi.ledger.EmiTbService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * EMI wallet engine (chapter 13 Stage 2) — PSD-3 regulated, NAD-only, par-redeemable.
 *
 * Money truth: TigerBeetle book 8xx (one ledger). A customer wallet is CREDIT_BOUND
 * (balance = credits − debits ≥ 0 — a wallet can never go negative), the float mirror is
 * DEBIT_BOUND. Every movement is: journal row (PENDING, deterministic transfer id) →
 * TB transfer → journal row (BOOKED). DB transactions never span the TigerBeetle call
 * (platform §2d); unknown-transport replays submit the SAME transfer id and are
 * idempotent at TB (exists ⇒ success).
 *
 * Idempotency: DEPOSIT (kind, bank statement ref) is unique in the DB — a replayed bank
 * credit can never double-credit a wallet. Redemptions consume the daily cap only on
 * BOOKED (a definitively rejected redemption never eats cap). Turnover increments happen
 * under the wallet's fair striped lock (KeyLocks) — DB @Version remains the truth.
 */
@Slf4j
@Service
public class EmiWalletService {

    private static final String CODE_NAD = "NAD";

    private final WalletRepository wallets;
    private final WalletJournalRepository journals;
    private final WalletDailyTurnoverRepository turnover;
    private final EmiTbService tb;
    private final EmiProperties props;
    private final KeyLocks locks;
    private final TransactionTemplate tx;

    public EmiWalletService(WalletRepository wallets, WalletJournalRepository journals,
                            WalletDailyTurnoverRepository turnover, EmiTbService tb,
                            EmiProperties props, KeyLocks locks, TransactionTemplate tx) {
        this.wallets = wallets;
        this.journals = journals;
        this.turnover = turnover;
        this.tb = tb;
        this.props = props;
        this.locks = locks;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ open

    public Wallet open(UUID personId, String walletRef) {
        return locks.withLock("open", personId, () -> {
            if (!props.getWalletCurrency().equals(CODE_NAD)) {
                // config drift guard: PSD-3 allows NAD-only wallets
                throw new ApiException(HttpStatus.CONFLICT, "EMI_CUR_NAD_ONLY",
                    "wallet currency must be NAD (PSD-3 §9.1.1)");
            }
            var existing = wallets.findByWalletRef(walletRef);
            if (existing.isPresent()) return existing.get();

            UUID walletId = UUID.randomUUID();
            var result = tb.createAccounts(List.of(
                // float mirror: CREDITS_MUST_NOT_EXCEED_DEBITS (asset-like) — created once,
                // deterministic id, identical re-dispatch is an idempotent success
                new EmiTbService.AccountSpec(Uuid5.floatAccountId(), tb.bookId(), 100,
                    tb.flagFor(EmiTbService.Bound.DEBIT_BOUND, true),
                    Uuid5.fromName("emi:code:float"), 0, 0),
                new EmiTbService.AccountSpec(Uuid5.walletAccountId(walletId), tb.bookId(), 200,
                    tb.flagFor(EmiTbService.Bound.CREDIT_BOUND, true),
                    Uuid5.fromName("emi:code:wallet"), 0, 0)));
            if (result instanceof EmiTbResult.DefinitiveRejection r) {
                throw new ApiException(HttpStatus.CONFLICT, "EMI_TB_REJECTED", r.detail());
            }
            if (result instanceof EmiTbResult.UnknownTransport r) {
                // account creation is deterministic-id idempotent: safe to re-run on retry
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMI_TB_UNKNOWN",
                    "TigerBeetle unavailable, wallet open not confirmed: " + r.detail());
            }

            Wallet wallet = new Wallet();
            wallet.id = walletId;
            wallet.walletRef = walletRef;
            wallet.personId = personId;
            wallet.currency = CODE_NAD;
            wallet.status = Wallet.Status.ACTIVE;
            wallet.dailyLimitMinor = BigDecimal.valueOf(props.getCaps().getDailyRedeemMinor());
            wallet.tbAccountId = Uuid5.walletAccountId(walletId);
            wallet.openedAt = Instant.now();
            return tx.execute(s -> wallets.save(wallet));
        });
    }

    // ------------------------------------------------------------------ deposit

    public WalletJournal deposit(UUID walletId, long amountMinor, String bankReference) {
        if (bankReference == null || bankReference.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_REF_REQUIRED",
                "deposit requires the bank statement reference");
        }
        return locks.withLock("deposit", walletId, () -> {
            // idempotency first: a replayed bank reference never double-credits
            var replayed = journals.findByKindAndReference(WalletJournal.Kind.DEPOSIT,
                bankRefKey(walletId, bankReference));
            if (replayed.isPresent()) return replayed.get();

            Wallet wallet = walletOr404(walletId);
            validateActive(wallet);
            requirePositive(amountMinor);

            UUID journalId = UUID.randomUUID();
            WalletJournal journal = tx.execute(s -> {
                WalletJournal j = new WalletJournal();
                j.id = journalId;
                j.walletId = walletId;
                j.kind = WalletJournal.Kind.DEPOSIT;
                j.currency = CODE_NAD;
                j.amountMinor = BigDecimal.valueOf(amountMinor);
                j.reference = bankRefKey(walletId, bankReference);
                j.tbTransferId = Uuid5.journalTransferId(journalId);
                j.state = WalletJournal.State.PENDING;
                j.createdAt = Instant.now();
                j.updatedAt = j.createdAt;
                return journals.save(j);
            });

            return book(wallet, journal);
        });
    }

    // ------------------------------------------------------------------ redeem

    public WalletJournal redeem(UUID walletId, long amountMinor) {
        // demo/internal form (no idempotency key — UNIQUE(kind, NULL) is unconstrained)
        return redeem(walletId, amountMinor, null);
    }

    /**
     * Par redemption (PSD-3 §9.1.1), idempotent on the caller's Idempotency-Key: the key
     * becomes the journal reference via UNIQUE (kind, reference) — a replayed key returns
     * the original journal untouched, never a second payout.
     */
    public WalletJournal redeem(UUID walletId, long amountMinor, String idempotencyKey) {
        if (idempotencyKey != null && idempotencyKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_REF_REQUIRED",
                "redemption requires an Idempotency-Key");
        }
        return locks.withLock("redeem", walletId, () -> {
            // idempotency first: a replayed key returns the original redemption
            WalletJournal replayed = idempotencyKey == null ? null
                : journals.findByKindAndReference(WalletJournal.Kind.REDEMPTION,
                    redeemRefKey(walletId, idempotencyKey)).orElse(null);
            if (replayed != null) {
                return replayed;
            }

            Wallet wallet = walletOr404(walletId);
            validateActive(wallet);
            requirePositive(amountMinor);

            var balance = tb.lookupBalance(wallet.tbAccountId);
            if (balance.isEmpty()) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMI_TB_UNKNOWN",
                    "wallet balance not readable; redemption not attempted");
            }
            long available = balance.get().creditsPosted() - balance.get().debitsPosted();
            if (amountMinor > available) {
                throw new ApiException(HttpStatus.CONFLICT, "EMI_INSUFFICIENT",
                    "redeem %d exceeds available %d minor".formatted(amountMinor, available));
            }

            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            long redeemedToday = turnover.findByWalletIdAndTurnDate(walletId, today)
                .map(t -> t.redeemedMinor.longValueExact()).orElse(0L);
            if (redeemedToday + amountMinor > props.getCaps().getDailyRedeemMinor()) {
                throw new ApiException(HttpStatus.CONFLICT, "EMI_DAILY_CAP",
                    "daily redemption cap %d minor reached (%d booked today)"
                        .formatted(props.getCaps().getDailyRedeemMinor(), redeemedToday));
            }

            UUID journalId = UUID.randomUUID();
            WalletJournal journal = tx.execute(s -> {
                WalletJournal j = new WalletJournal();
                j.id = journalId;
                j.walletId = walletId;
                j.kind = WalletJournal.Kind.REDEMPTION;
                j.currency = CODE_NAD;
                j.amountMinor = BigDecimal.valueOf(amountMinor);
                j.reference = idempotencyKey == null ? null : redeemRefKey(walletId, idempotencyKey);
                j.tbTransferId = Uuid5.journalTransferId(journalId);
                j.state = WalletJournal.State.PENDING;
                j.createdAt = Instant.now();
                j.updatedAt = j.createdAt;
                return journals.save(j);
            });

            WalletJournal booked = book(wallet, journal);
            if (booked.state.equals(WalletJournal.State.BOOKED)) {
                tx.execute(s -> {
                    var row = turnover.findByWalletIdAndTurnDate(walletId, today)
                        .orElseGet(() -> {
                            var t = new WalletDailyTurnover();
                            t.walletId = walletId;
                            t.turnDate = today;
                            t.redeemedMinor = BigDecimal.ZERO;
                            return t;
                        });
                    row.redeemedMinor = BigDecimal.valueOf(redeemedToday + amountMinor);
                    row.updatedAt = Instant.now();
                    turnover.save(row);
                    return null;
                });
            }
            return booked;
        });
    }

    // ------------------------------------------------------------------ balance

    public long balance(UUID walletId) {
        Wallet wallet = walletOr404(walletId);
        return tb.lookupBalance(wallet.tbAccountId)
            .map(b -> b.creditsPosted() - b.debitsPosted())
            .orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMI_TB_UNKNOWN",
                "balance not readable"));
    }

    // ------------------------------------------------------------------ internals

    /** PENDING journal row → TB transfer (same id on replay) → BOOKED/FAILED. */
    private WalletJournal book(Wallet wallet, WalletJournal journal) {
        long amount = journal.amountMinor.longValueExact();
        UUID tbId = journal.tbTransferId;
        var spec = new EmiTbService.TransferSpec(
            tbId,
            /* debit: */ journal.kind.equals(WalletJournal.Kind.DEPOSIT)
                ? Uuid5.floatAccountId()          // deposit: float mirror debited (float leaves)
                : wallet.tbAccountId,             // redemption: wallet debited
            /* credit: */ journal.kind.equals(WalletJournal.Kind.DEPOSIT)
                ? wallet.tbAccountId              // deposit: wallet credited
                : Uuid5.floatAccountId(),         // redemption: float mirror credited back
            amount, tb.bookId(), 1 /* JE code */,
            0 /* flags: settled, no linked chain on simple two-leg JE */,
            journal.id, 0, 0,
            null /* not a pending-transfer resolution */,
            0 /* no hold timeout */);

        var result = tb.createTransfers(List.of(spec));
        if (result instanceof EmiTbResult.Ok) {
            journal.state = WalletJournal.State.BOOKED;
        } else if (result instanceof EmiTbResult.DefinitiveRejection rejected) {
            journal.state = WalletJournal.State.FAILED;
            journal.failureCode = rejected.detail();
            tx.execute(s -> {
                journal.updatedAt = Instant.now();
                return journals.save(journal);
            });
            // surface AFTER persisting the terminal state (fail-safe row first)
            throw new ApiException(HttpStatus.CONFLICT, "EMI_TB_REJECTED",
                "transfer id " + tbId + " rejected: " + rejected.detail());
        } else if (result instanceof EmiTbResult.UnknownTransport unknown) {
            // leave PENDING; replay submits the same transfer id
            journal.state = WalletJournal.State.PENDING;
            tx.execute(s -> {
                journal.updatedAt = Instant.now();
                return journals.save(journal);
            });
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMI_TB_UNKNOWN",
                "transfer id " + tbId + " submitted-unknown (" + unknown.detail()
                    + "); safe to replay the same id");
        }
        return tx.execute(s -> {
            journal.updatedAt = Instant.now();
            return journals.save(journal);
        });
    }

    /** Namespaces one bank ref per wallet so cross-wallet ref reuse cannot collide. */
    private static String bankRefKey(UUID walletId, String bankReference) {
        return walletId + ":" + bankReference;
    }

    /** Redemption idempotency reference — kind-unique with the bank-ref namespace disjoint. */
    private static String redeemRefKey(UUID walletId, String idempotencyKey) {
        // Idempotency-Key values are sanitized to the safe character class before they are
        // used as a reference token (never logged, never echoed).
        String safe = idempotencyKey.replaceAll("[^A-Za-z0-9_.-]", "");
        if (safe.isEmpty() || safe.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_REF_REQUIRED",
                "Idempotency-Key must be 1-128 chars of [A-Za-z0-9_.-]");
        }
        return "redeem:" + walletId + ":" + safe;
    }

    private Wallet walletOr404(UUID walletId) {
        return wallets.findById(walletId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EMI_WALLET_404",
                "wallet not found: " + walletId));
    }

    private static void validateActive(Wallet wallet) {
        if (!wallet.status.equals(Wallet.Status.ACTIVE)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMI_WALLET_STATE",
                "wallet %s requires ACTIVE, saw %s".formatted(wallet.id, wallet.status));
        }
        if (!CODE_NAD.equals(wallet.currency)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMI_CUR_NAD_ONLY",
                "wallet currency must be NAD (PSD-3 §9.1.1)");
        }
    }

    private static void requirePositive(long amountMinor) {
        if (amountMinor <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMI_AMOUNT",
                "amount must be positive minor units");
        }
    }
}
