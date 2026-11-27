package com.fsp.emi.recon;

import com.fsp.emi.common.Uuid5;
import com.fsp.emi.ledger.EmiTbService;
import com.fsp.emi.wallet.Wallet;
import com.fsp.emi.wallet.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EMI custody recon face (Stage 6; chapter 13 §8): the float must back wallets PLUS the
 * held-funds accounts (bill/SKU/fee/tax clearing). Wallet liability sum is Σ TB
 * credits−debits over the bounded ACTIVE page; unknown wallet accounts count as unproven
 * (never zero); a never-used clearing account is absent in TB = 0 parked (provably empty);
 * float net is the asset-side mirror (null when unprovable). Sums stay long — TB longs
 * are safe for ≤ EUR 9e13 at minor units (chapter 13 §8 overflow rule).
 */
class EmiReconServiceTest {

    WalletRepository wallets;
    EmiTbService tb;
    EmiReconService service;

    @BeforeEach
    void setUp() {
        wallets = mock(WalletRepository.class);
        tb = mock(EmiTbService.class);
        service = new EmiReconService(wallets, tb);
    }

    private static Wallet wallet(UUID accountId, String currency) {
        Wallet w = new Wallet();
        w.id = UUID.randomUUID();
        w.tbAccountId = accountId;
        w.currency = currency;
        w.status = Wallet.Status.ACTIVE;
        w.openedAt = Instant.EPOCH;
        return w;
    }

    @Test
    void custodyLineSumsTbWalletBalancesAgainstFloatNet() {
        Wallet a = wallet(UUID.randomUUID(), "NAD");
        Wallet b = wallet(UUID.randomUUID(), "NAD");
        Wallet c = wallet(UUID.randomUUID(), "USD");
        when(wallets.findAllByStatusOrderByOpenedAtAscIdAsc(any(String.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(a, b, c), PageRequest.of(0, 10), 3));
        // batch lookup keyed by request size: 3 ids = wallet page; 4 ids = held-funds page
        when(tb.lookupBalances(any())).thenAnswer(inv -> {
            List<UUID> ids = inv.getArgument(0);
            if (ids.size() == 3) return List.of(
                Optional.of(new EmiTbService.Balance(500, 3000, 0, 0)),   // a: liability 2500
                Optional.of(new EmiTbService.Balance(1200, 1200, 0, 0)),  // b: liability 0
                Optional.of(new EmiTbService.Balance(400, 900, 0, 0)));   // c: liability 500
            if (ids.size() == 4 && ids.get(0).equals(Uuid5.billClearingAccountId("NAD")))
                return List.of( // NAD held: bill 2500, sku absent, fee 30, tax 20
                    Optional.of(new EmiTbService.Balance(0, 2500, 0, 0)),
                    Optional.empty(),
                    Optional.of(new EmiTbService.Balance(0, 30, 0, 0)),
                    Optional.of(new EmiTbService.Balance(0, 20, 0, 0)));
            return List.of(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        });
        when(tb.lookupBalance(any(UUID.class))).thenReturn(Optional.of(
            new EmiTbService.Balance(3000, 0, 0, 0))); // float holds 3000 (asset-side debit)

        var snap = service.snapshot();

        assertThat(snap.custody()).hasSize(2);
        var nad = snap.custody().stream().filter(l -> "NAD".equals(l.currency())).findFirst().orElseThrow();
        assertThat(nad.walletCount()).isEqualTo(2);
        assertThat(nad.walletSumMinor()).isEqualTo("2500");
        assertThat(nad.heldSumMinor()).isEqualTo("2550");
        assertThat(nad.floatNetMinor()).isEqualTo("3000");
        assertThat(nad.walletsUnproven()).isZero();
        var usd = snap.custody().stream().filter(l -> "USD".equals(l.currency())).findFirst().orElseThrow();
        assertThat(usd.walletSumMinor()).isEqualTo("500");
        assertThat(usd.heldSumMinor()).isEqualTo("0");
    }

    @Test
    void unknownTbAccountCountsUnprovenAndNeverDefaultsToZero() {
        Wallet a = wallet(UUID.randomUUID(), "NAD");
        Wallet b = wallet(UUID.randomUUID(), "NAD");
        when(wallets.findAllByStatusOrderByOpenedAtAscIdAsc(any(String.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(a, b), PageRequest.of(0, 10), 2));
        when(tb.lookupBalances(any())).thenReturn(List.of(
            Optional.of(new EmiTbService.Balance(0, 4700, 0, 0)),
            Optional.empty()),
            List.of(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        when(tb.lookupBalance(any(UUID.class))).thenReturn(Optional.of(
            new EmiTbService.Balance(4700, 0, 0, 0)));

        var snap = service.snapshot();

        var line = snap.custody().get(0);
        assertThat(line.walletsUnproven()).isEqualTo(1);
        assertThat(line.walletSumMinor()).isEqualTo("4700"); // only the proven wallet sums
        assertThat(line.heldSumMinor()).isEqualTo("0");
    }

    @Test
    void unknownFloatIsReportedAsNullNotZero() {
        Wallet a = wallet(UUID.randomUUID(), "NAD");
        when(wallets.findAllByStatusOrderByOpenedAtAscIdAsc(any(String.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(a), PageRequest.of(0, 10), 1));
        when(tb.lookupBalances(any())).thenReturn(List.of(
            Optional.of(new EmiTbService.Balance(0, 4700, 0, 0))),
            List.of(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        when(tb.lookupBalance(any(UUID.class))).thenReturn(Optional.empty());

        var snap = service.snapshot();

        assertThat(snap.custody().get(0).floatNetMinor()).isNull();
    }

    @Test
    void walletPageIsBoundedDeterministically() {
        when(wallets.findAllByStatusOrderByOpenedAtAscIdAsc(any(String.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        service.snapshot();
        org.mockito.Mockito.verify(wallets).findAllByStatusOrderByOpenedAtAscIdAsc(
            any(String.class),
            argThat(p -> p.getPageSize() == EmiReconService.MAX_WALLETS
                && p.getSort().toString().contains("openedAt")
                && p.getSort().toString().contains("id")));
    }

    @Test
    void emptyBookYieldsEmptySnapshot() {
        when(wallets.findAllByStatusOrderByOpenedAtAscIdAsc(any(String.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        assertThat(service.snapshot().custody()).isEmpty();
    }
}
