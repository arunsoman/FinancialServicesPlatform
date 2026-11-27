package com.fsp.emi.ledger;

import com.fsp.emi.config.EmiProperties;
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
 * The only class (besides EmiTigerBeetleConfig) that may import com.tigerbeetle.*
 * (platform §2e — EMI copy). Thin, result-typed wrapper over the 0.17.9 client:
 * every write returns a sealed EmiTbResult; negative paths are incident-logged here.
 * Account semantics on the EMI book:
 *   wallet = CREDIT_BOUND (liability-like; balance = credits − debits ≥ 0)
 *   float  = DEBIT_BOUND  (asset-like;      balance = debits − credits ≥ 0)
 */
@Slf4j
@Service
public class EmiTbService {

    public enum Bound { NONE, DEBIT_BOUND, CREDIT_BOUND }

    /** Two-phase (Stage 3) transfer flags — declared here so ONLY this class touches com.tigerbeetle. */
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
     * two-phase (Stage 3) transfers set exactly one of them:
     *   PENDING hold  → flags = TransferFlags.PENDING, timeoutSeconds = hold window
     *   commit        → flags = TransferFlags.POST_PENDING_TRANSFER, pendingId = hold id
     *   void          → flags = TransferFlags.VOID_PENDING_TRANSFER, pendingId = hold id
     * Post/void must repeat debit/credit/ledger/code and amount exactly, or zero them
     * (exact repeats make replays byte-identical — idempotent at TB). Note: a commit/void
     * transfer references a pending transfer via {@code pending_id}; it does NOT move new
     * money, it resolves the reservation.
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

    public EmiTbService(Client client, EmiProperties props) {
        this.client = client;
        this.bookId = props.getTigerBeetle().getBookId();
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

    /** Recon face (Stage 6): batch-lookup balances for many accounts (TB is the
     *  authoritative record; batching keeps the deep custody probe to a few RPCs).
     *  Returns balances in the SAME ORDER as the given ids; accounts unknown to TB are
     *  null entries (probes must treat them as "cannot prove", not as zero). */
    public List<java.util.Optional<Balance>> lookupBalances(List<UUID> accountIds) {
        List<java.util.Optional<Balance>> out = new ArrayList<>(accountIds.size());
        if (accountIds.isEmpty()) return out;
        int chunk = 500;
        for (int start = 0; start < accountIds.size(); start += chunk) {
            List<UUID> slice = accountIds.subList(start, Math.min(start + chunk, accountIds.size()));
            IdBatch ids = new IdBatch(slice.size());
            for (UUID id : slice) {
                ids.add(UInt128.asBytes(id));
            }
            try {
                AccountBatch balances = client.lookupAccounts(ids);
                java.util.Map<UUID, Balance> byId = new java.util.HashMap<>();
                while (balances.next()) {
                    byId.put(UInt128.asUUID(balances.getId()),
                        new Balance(saturate(balances.getDebitsPosted()),
                            saturate(balances.getCreditsPosted()),
                            saturate(balances.getDebitsPending()),
                            saturate(balances.getCreditsPending())));
                }
                for (UUID id : slice) {
                    out.add(java.util.Optional.ofNullable(byId.get(id)));
                }
            } catch (Exception e) {
                log.error("emi_tb_batch_balance_unknown_transport count={}", slice.size(), e);
                for (UUID ignored : slice) out.add(java.util.Optional.empty());
            }
        }
        return out;
    }

    public EmiTbResult createAccounts(List<AccountSpec> specs) {
        if (specs.isEmpty()) return new EmiTbResult.Ok();
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
                        log.debug("emi_tb_accounts_exists index={}", results.getPosition());
                    }
                    continue;
                }
                batch.beforeFirst();
                batch.setPosition(results.getPosition());
                failures.add("index=%d status=%s".formatted(results.getPosition(), status));
            }
            if (!failures.isEmpty()) {
                log.warn("emi_tb_accounts_rejected count={} failures={}", specs.size(), failures);
                return new EmiTbResult.DefinitiveRejection("createAccounts rejected: " + failures);
            }
            log.debug("emi_tb_accounts_ok count={}", specs.size());
            return new EmiTbResult.Ok();
        } catch (Exception e) {
            log.error("emi_tb_accounts_unknown_transport count={}", specs.size(), e);
            return new EmiTbResult.UnknownTransport("createAccounts transport failure: " + e.getMessage());
        }
    }

    public EmiTbResult createTransfers(List<TransferSpec> specs) {
        if (specs.isEmpty()) return new EmiTbResult.Ok();
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
                // success; exists_with_different_* rejects (drift guard).
                if (status == CreateTransferStatus.Created || status == CreateTransferStatus.Exists
                        || status == CreateTransferStatus.LinkedEventChainOpen) {
                    if (status == CreateTransferStatus.Exists) {
                        log.debug("emi_tb_transfers_exists index={}", results.getPosition());
                    }
                    continue;
                }
                batch.beforeFirst();
                batch.setPosition(results.getPosition());
                failures.add("index=%d status=%s".formatted(results.getPosition(), status));
            }
            if (!failures.isEmpty()) {
                log.warn("emi_tb_transfers_rejected count={} failures={}", specs.size(), failures);
                return new EmiTbResult.DefinitiveRejection("createTransfers rejected: " + failures);
            }
            log.debug("emi_tb_transfers_ok count={}", specs.size());
            return new EmiTbResult.Ok();
        } catch (Exception e) {
            log.error("emi_tb_transfers_unknown_transport count={}", specs.size(), e);
            return new EmiTbResult.UnknownTransport("createTransfers transport failure: " + e.getMessage());
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
            log.error("emi_tb_balance_unknown_transport account={}", tbAccountId, e);
            return Optional.empty();
        }
    }

    /** u64 sums can exceed Long.MAX in theory — saturating read with an incident note. */
    private static long saturate(BigInteger v) {
        long out = v.longValueExact();
        if (out < 0) { // unsigned above Long.MAX_VALUE
            log.warn("emi_tb_balance_overflow saturation to Long.MAX_VALUE value={}", v);
            return Long.MAX_VALUE;
        }
        return out;
    }
}
