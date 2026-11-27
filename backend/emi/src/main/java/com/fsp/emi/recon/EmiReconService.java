package com.fsp.emi.recon;

import com.fsp.emi.common.Uuid5;
import com.fsp.emi.ledger.EmiTbService;
import com.fsp.emi.wallet.Wallet;
import com.fsp.emi.wallet.WalletRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles the read-only custody snapshot (plan Stage 6, chapter 13 §8). Bounded paging
 * (platform §2f); TB reads are outside any DB transaction (§2d); the ledger (not a DB
 * column) is the balance of record.
 *
 * <p><b>Custody invariant (plan §1.2 / PSD-3):</b> the float mirror (asset side) must
 * equal every liability sitting on the EMI book — customer wallets <b>plus</b> the
 * held-funds accounts (bill clearing, SKU clearing, fee clearing, tax liability: money
 * that left a wallet but is still physically in the float until externally settled).</p>
 */
@Service
public class EmiReconService {

    /** Deep probe bound: wallets are paged deterministically; re-poll cadence covers rest. */
    static final int MAX_WALLETS = 20_000;

    private final WalletRepository wallets;
    private final EmiTbService tb;

    public EmiReconService(WalletRepository wallets, EmiTbService tb) {
        this.wallets = wallets;
        this.tb = tb;
    }

    public EmiReconSnapshot.Snapshot snapshot() {
        // Deterministic bounded page over ACTIVE wallets, batch-lookup their TB accounts.
        List<Wallet> scoped = wallets
            .findAllByStatusOrderByOpenedAtAscIdAsc(Wallet.Status.ACTIVE,
                PageRequest.of(0, MAX_WALLETS, Sort.by(Sort.Direction.ASC, "openedAt", "id")))
            .getContent();

        Map<String, Agg> byCurrency = new LinkedHashMap<>();
        if (!scoped.isEmpty()) {
            List<UUID> accountIds = scoped.stream().map(w -> w.tbAccountId).toList();
            List<java.util.Optional<EmiTbService.Balance>> balances = tb.lookupBalances(accountIds);
            for (int i = 0; i < scoped.size(); i++) {
                Wallet w = scoped.get(i);
                Agg agg = byCurrency.computeIfAbsent(w.currency, c -> new Agg());
                agg.wallets++;
                java.util.Optional<EmiTbService.Balance> bal = balances.get(i);
                if (bal.isEmpty()) {
                    agg.unproven++; // cannot prove → the worker treats as probe failure
                } else {
                    // wallet is liability-side: customer balance = credits − debits
                    agg.walletSum += bal.get().creditsPosted() - bal.get().debitsPosted();
                }
            }
        }

        // Held funds per currency present in the wallet population (bill/sku/fee/tax
        // clearing): credited when money leaves a wallet, debited on external settlement.
        // A never-used clearing account may not exist in TB — absence = 0 parked (its
        // balance is provably empty by construction; wallets differ: absence there is
        // unprovable because a provisioned wallet MUST have a ledger account).
        for (Map.Entry<String, Agg> e : byCurrency.entrySet()) {
            String ccy = e.getKey();
            List<UUID> heldIds = List.of(Uuid5.billClearingAccountId(ccy),
                Uuid5.skuClearingAccountId(ccy), Uuid5.feeClearingAccountId(ccy),
                Uuid5.taxLiabilityAccountId(ccy));
            List<java.util.Optional<EmiTbService.Balance>> held = tb.lookupBalances(heldIds);
            for (java.util.Optional<EmiTbService.Balance> h : held) {
                h.ifPresent(b -> e.getValue().heldSum += b.creditsPosted() - b.debitsPosted());
            }
            // Float net per currency (asset-side mirror; credited on deposit, debited on
            // deposit-leave): null when the account cannot be proved (never default to 0).
            e.getValue().floatNet = tb.lookupBalance(Uuid5.floatAccountId())
                .map(b -> Long.toString(b.net())).orElse(null);
        }

        List<EmiReconSnapshot.CustodyLine> lines = new ArrayList<>();
        for (Map.Entry<String, Agg> e : byCurrency.entrySet()) {
            Agg a = e.getValue();
            lines.add(new EmiReconSnapshot.CustodyLine(e.getKey(), a.wallets,
                Long.toString(a.walletSum), Long.toString(a.heldSum), a.floatNet, a.unproven));
        }

        return new EmiReconSnapshot.Snapshot(Instant.now(), List.copyOf(lines));
    }

    private static final class Agg {
        long wallets;
        long walletSum;
        long heldSum;
        String floatNet;
        int unproven;
    }
}
