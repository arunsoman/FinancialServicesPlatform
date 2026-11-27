package com.fsp.core.ledger;

import com.fsp.core.common.ApiException;
import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.AccountFlags;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateAccountStatus;
import com.tigerbeetle.CreateTransferStatus;
import com.tigerbeetle.IdBatch;
import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.TransferFlags;
import com.tigerbeetle.UInt128;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only class (besides config) that may import com.tigerbeetle.* (AGENTS.md §3.5).
 * Thin, result-typed wrapper over the 0.17.9 client: every write returns a sealed TbResult.
 * Negative paths are incident-logged here (AGENTS.md §2c): definitive rejection = WARN,
 * transport failure = ERROR with the exception attached.
 */
@Slf4j
@Service
public class TbService {

    public enum Bound { NONE, DEBIT_BOUND, CREDIT_BOUND }

    public record AccountSpec(UUID id, int ledger, int code, int flags,
                              UUID userData128, long userData64, int userData32) {}

    public record TransferSpec(UUID id, UUID debitAccountId, UUID creditAccountId, long amount,
                               int ledger, int code, int flags,
                               UUID userData128, long userData64, int userData32) {}

    public record Balance(long debitsPosted, long creditsPosted, long debitsPending, long creditsPending) {
        public long net() { return debitsPosted - creditsPosted; }
    }

    private final Client client;

    public TbService(Client client) {
        this.client = client;
    }

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

    public int transferFlagLinked(boolean linked) {
        return linked ? TransferFlags.LINKED : TransferFlags.NONE;
    }

    public TbResult createAccounts(List<AccountSpec> specs) {
        if (specs.isEmpty()) return new TbResult.Ok();
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
                // The results array carries a row for every position in this client; 'Created' is the
                // success status — only real rejections fail the batch. 'exists' (identical id
                // re-dispatched, e.g. a CoA provision/ensure retry after an UnknownTransport — the
                // deterministic UUIDs are stable) is an idempotent success. Flag/user-data/ledger
                // drift still rejects below via the exists_with_different_* statuses.
                if (status == CreateAccountStatus.Created || status == CreateAccountStatus.Exists) {
                    if (status == CreateAccountStatus.Exists) {
                        log.debug("tb_create_accounts_exists index={}", results.getPosition());
                    }
                    continue;
                }
                batch.beforeFirst();
                batch.setPosition(results.getPosition());
                failures.add("index=%d idLow=%d status=%s".formatted(results.getPosition(),
                    batch.isValidPosition() ? batch.getId(UInt128.LeastSignificant) : -1L, status));
            }
            if (!failures.isEmpty()) {
                log.warn("tb_create_accounts_rejected count={} failures={}", specs.size(), failures);
                return new TbResult.DefinitiveRejection("createAccounts rejected: " + failures);
            }
            log.debug("tb_create_accounts_ok count={}", specs.size());
            return new TbResult.Ok();
        } catch (Exception e) {
            log.error("tb_create_accounts_unknown_transport count={}", specs.size(), e);
            return new TbResult.UnknownTransport("createAccounts transport failure: " + e.getMessage());
        }
    }

    public TbResult createTransfers(List<TransferSpec> specs) {
        if (specs.isEmpty()) return new TbResult.Ok();
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
        }
        try {
            var results = client.createTransfers(batch);
            List<String> failures = new ArrayList<>();
            while (results.next()) {
                CreateTransferStatus status = results.getStatus();
                // 'Created' is success; LinkedEventChainOpen is success-by-design for the
                // intermediate legs of a linked chain (the final leg resolves the chain).
                if (status == CreateTransferStatus.Created || status == CreateTransferStatus.LinkedEventChainOpen) continue;
                batch.beforeFirst();
                batch.setPosition(results.getPosition());
                failures.add("index=%d idLow=%d status=%s".formatted(results.getPosition(),
                    batch.isValidPosition() ? batch.getId(UInt128.LeastSignificant) : -1L, status));
            }
            if (!failures.isEmpty()) {
                log.warn("tb_create_transfers_rejected count={} failures={}", specs.size(), failures);
                return new TbResult.DefinitiveRejection("createTransfers rejected: " + failures);
            }
            log.debug("tb_create_transfers_ok count={} amountTotal={}", specs.size(),
                specs.stream().mapToLong(TransferSpec::amount).sum());
            return new TbResult.Ok();
        } catch (Exception e) {
            log.error("tb_create_transfers_unknown_transport count={} firstId={}", specs.size(),
                specs.isEmpty() ? "n/a" : specs.getFirst().id(), e);
            return new TbResult.UnknownTransport("createTransfers transport failure: " + e.getMessage());
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
            log.error("tb_lookup_accounts_unknown_transport tbAccountId={}", tbAccountId, e);
            throw ApiException.serviceUnavailable("LEDGER_UNAVAILABLE",
                "TigerBeetle lookup failed: " + e.getMessage());
        }
    }

    /** For each id (in order), whether the transfer exists in TigerBeetle. */
    public boolean[] transfersExist(List<UUID> ids) {
        boolean[] out = new boolean[ids.size()];
        if (ids.isEmpty()) return out;
        try {
            IdBatch lookup = new IdBatch(ids.size());
            for (UUID id : ids) lookup.add(UInt128.asBytes(id));
            TransferBatch found = client.lookupTransfers(lookup);
            List<UUID> present = new ArrayList<>(ids.size());
            while (found.next()) {
                present.add(UInt128.asUUID(found.getId()));
            }
            for (int i = 0; i < ids.size(); i++) {
                out[i] = present.contains(ids.get(i));
            }
            return out;
        } catch (Exception e) {
            log.error("tb_lookup_transfers_unknown_transport ids={}", ids, e);
            throw ApiException.serviceUnavailable("LEDGER_UNAVAILABLE",
                "TigerBeetle lookupTransfers failed: " + e.getMessage());
        }
    }

    /** u64 sums can exceed Long.MAX in theory — saturating read with an incident note. */
    private static long saturate(BigInteger v) {
        long out = v.longValueExact();
        if (out < 0) { // unsigned above Long.MAX_VALUE
            log.warn("tb_balance_overflow saturation to Long.MAX_VALUE value={}", v);
            return Long.MAX_VALUE;
        }
        return out;
    }
}
