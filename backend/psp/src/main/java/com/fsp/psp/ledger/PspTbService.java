package com.fsp.psp.ledger;

import com.fsp.psp.config.PspProperties;
import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.AccountFlags;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateAccountStatus;
import com.tigerbeetle.CreateTransferStatus;
import com.tigerbeetle.IdBatch;
import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.UInt128;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only class (besides PspTigerBeetleConfig) that may import com.tigerbeetle.*
 * (platform §2e — PSP copy). Thin, result-typed wrapper over the 0.17.9 client:
 * every write returns a sealed PspTbResult; negative paths are incident-logged here.
 * Account semantics on the PSP book (plan §1.1):
 *   settlement-float  = DEBIT_BOUND  (asset; balance = debits − credits ≥ 0)
 *   merchant-payable  = CREDIT_BOUND (liability; balance = credits − debits ≥ 0)
 *   clearing / fee-revenue = NONE + HISTORY (nominal in-flight and revenue accounts)
 */
@Slf4j
@Service
public class PspTbService {

    public enum Bound { NONE, DEBIT_BOUND, CREDIT_BOUND }

    /** Two-phase transfer flags — declared here so ONLY this class touches com.tigerbeetle. */
    public static final int FLAG_PENDING = com.tigerbeetle.TransferFlags.PENDING;
    public static final int FLAG_POST_PENDING = com.tigerbeetle.TransferFlags.POST_PENDING_TRANSFER;
    public static final int FLAG_VOID_PENDING = com.tigerbeetle.TransferFlags.VOID_PENDING_TRANSFER;
    /** Linked-chain flag: every transfer so flagged (except the last of the chain) closes
     *  on the previous one, making the whole batch atomic — all legs commit or all fail. */
    public static final int FLAG_LINKED = com.tigerbeetle.TransferFlags.LINKED;

    public record AccountSpec(UUID id, int ledger, int code, int flags,
                              UUID userData128, long userData64, int userData32) {}

    /**
     * One TB transfer. Plain transfers set {@code pendingId = null, timeoutSeconds = 0};
     * two-phase transfers set exactly one of them:
     *   PENDING capture → flags = PENDING, timeoutSeconds = settle grace window
     *   commit (post-pending) → flags = POST_PENDING, pendingId = capture id
     *   void (void-pending)   → flags = VOID_PENDING, pendingId = capture id
     * Post/void must repeat debit/credit/ledger/code and amount exactly (or zero them)
     * so replays are byte-identical — idempotent at TB.
     */
    public record TransferSpec(UUID id, UUID debitAccountId, UUID creditAccountId, long amount,
                               int ledger, int code, int flags,
                               UUID userData128, long userData64, int userData32,
                               UUID pendingId, int timeoutSeconds) {}

    public record Balance(long debitsPosted, long creditsPosted, long debitsPending, long creditsPending) {
        /** Signed net position: debits − credits (positive when asset-like). */
        public long net() { return debitsPosted - creditsPosted; }
    }

    private final Client client;
    private final int bookId;

    public PspTbService(Client client, PspProperties props) {
        this.client = client;
        this.bookId = props.getTigerbeetle().getBookId();
    }

    public int bookId() { return bookId; }

    public int flagFor(Bound bound, boolean history) {
        int flags = AccountFlags.NONE;
        switch (bound) {
            case DEBIT_BOUND -> flags |= AccountFlags.CREDITS_MUST_NOT_EXCEED_DEBITS;
            case CREDIT_BOUND -> flags |= AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS;
            case NONE -> { /* no bound flags */ }
        }
        if (history) flags |= AccountFlags.HISTORY;
        return flags;
    }

    public PspTbResult createAccounts(List<AccountSpec> specs) {
        if (specs.isEmpty()) return new PspTbResult.Ok();
        AccountBatch batch = new AccountBatch(specs.size());
        for (AccountSpec s : specs) {
            batch.add();
            batch.setId(UInt128.asBytes(s.id()));
            batch.setLedger(s.ledger());
            batch.setCode(s.code() & 0xFFFF);
            batch.setFlags(s.flags());
            batch.setUserData128(s.userData128() == null
                ? new byte[16]
                : UInt128.asBytes(s.userData128()));
            batch.setUserData64(s.userData64());
            batch.setUserData32(s.userData32());
        }
        try {
            var results = client.createAccounts(batch);
            List<String> failures = new ArrayList<>();
            while (results.next()) {
                CreateAccountStatus status = results.getStatus();
                // 'Created' is success; 'exists' (identical id re-dispatched — deterministic
                // UUIDs are stable) is an idempotent success; exists_with_different_* rejects.
                if (status == CreateAccountStatus.Created || status == CreateAccountStatus.Exists) {
                    if (status == CreateAccountStatus.Exists) {
                        log.debug("psp_tb_accounts_exists index={}", results.getPosition());
                    }
                    continue;
                }
                batch.beforeFirst();
                batch.setPosition(results.getPosition());
                failures.add("index=%d status=%s".formatted(results.getPosition(), status));
            }
            if (!failures.isEmpty()) {
                log.warn("psp_tb_accounts_rejected count={} failures={}", specs.size(), failures);
                return new PspTbResult.DefinitiveRejection("createAccounts rejected: " + failures);
            }
            log.debug("psp_tb_accounts_ok count={}", specs.size());
            return new PspTbResult.Ok();
        } catch (Exception e) {
            log.error("psp_tb_accounts_unknown_transport count={}", specs.size(), e);
            return new PspTbResult.UnknownTransport("createAccounts transport failure: " + e.getMessage());
        }
    }

    public PspTbResult createTransfers(List<TransferSpec> specs) {
        if (specs.isEmpty()) return new PspTbResult.Ok();
        TransferBatch batch = new TransferBatch(specs.size());
        for (TransferSpec s : specs) {
            batch.add();
            batch.setId(UInt128.asBytes(s.id()));
            batch.setDebitAccountId(UInt128.asBytes(s.debitAccountId()));
            batch.setCreditAccountId(UInt128.asBytes(s.creditAccountId()));
            batch.setAmount(s.amount());
            batch.setLedger(s.ledger());
            batch.setCode(s.code() & 0xFFFF);
            batch.setFlags(s.flags());
            batch.setUserData128(s.userData128() == null
                ? new byte[16]
                : UInt128.asBytes(s.userData128()));
            batch.setUserData64(s.userData64());
            batch.setUserData32(s.userData32());
            if (s.pendingId() != null) {
                batch.setPendingId(UInt128.asBytes(s.pendingId()));
            }
            if (s.timeoutSeconds() > 0) {
                batch.setTimeout(s.timeoutSeconds());
            }
        }
        try {
            var results = client.createTransfers(batch);
            List<String> failures = new ArrayList<>();
            while (results.next()) {
                CreateTransferStatus status = results.getStatus();
                // 'Created' is success; 'exists' (identical id replayed) is an idempotent
                // success; LinkedEventChainOpen is an idempotent success; exists_with_*
                // rejects (drift guard); LinkedEventChainClosed = the chain already resolved.
                if (status == CreateTransferStatus.Created || status == CreateTransferStatus.Exists
                        || status == CreateTransferStatus.LinkedEventChainOpen) {
                    if (status == CreateTransferStatus.Exists) {
                        log.debug("psp_tb_transfers_exists index={}", results.getPosition());
                    }
                    continue;
                }
                batch.beforeFirst();
                batch.setPosition(results.getPosition());
                failures.add("index=%d status=%s".formatted(results.getPosition(), status));
            }
            if (!failures.isEmpty()) {
                log.warn("psp_tb_transfers_rejected count={} failures={}", specs.size(), failures);
                return new PspTbResult.DefinitiveRejection("createTransfers rejected: " + failures);
            }
            log.debug("psp_tb_transfers_ok count={}", specs.size());
            return new PspTbResult.Ok();
        } catch (Exception e) {
            log.error("psp_tb_transfers_unknown_transport count={}", specs.size(), e);
            return new PspTbResult.UnknownTransport("createTransfers transport failure: " + e.getMessage());
        }
    }

    public Optional<Balance> lookupBalance(UUID tbAccountId) {
        try {
            AccountBatch balances = client.lookupAccounts(new IdBatch(UInt128.asBytes(tbAccountId)));
            if (balances.next()) {
                return Optional.of(new Balance(
                    saturate(balances.getDebitsPosted()),
                    saturate(balances.getCreditsPosted()),
                    saturate(balances.getDebitsPending()),
                    saturate(balances.getCreditsPending())));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.error("psp_tb_balance_unknown_transport account={}", tbAccountId, e);
            return Optional.empty();
        }
    }

    /** u64 sums can exceed Long.MAX in theory — saturating read with an incident note. */
    private static long saturate(BigInteger v) {
        long out = v.longValueExact();
        if (out < 0) { // unsigned above Long.MAX_VALUE
            log.warn("psp_tb_balance_overflow saturation to Long.MAX_VALUE value={}", v);
            return Long.MAX_VALUE;
        }
        return out;
    }
}
