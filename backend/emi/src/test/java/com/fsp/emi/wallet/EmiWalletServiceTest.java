package com.fsp.emi.wallet;

import com.fsp.emi.common.ApiException;
import com.fsp.emi.common.KeyLocks;
import com.fsp.emi.common.Uuid5;
import com.fsp.emi.config.EmiProperties;
import com.fsp.emi.ledger.EmiTbResult;
import com.fsp.emi.ledger.EmiTbService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Chapter 13 Stage 2 — EMI wallet engine unit tests (TB mocked at the service seam). */
class EmiWalletServiceTest {

    WalletRepository wallets;
    WalletJournalRepository journals;
    WalletDailyTurnoverRepository turnover;
    EmiTbService tb;
    KeyLocks locks = new KeyLocks();
    EmiWalletService service;

    UUID walletId = UUID.randomUUID();
    Wallet wallet;

    @BeforeEach
    void setUp() {
        wallets = mock(WalletRepository.class);
        journals = mock(WalletJournalRepository.class);
        turnover = mock(WalletDailyTurnoverRepository.class);
        tb = mock(EmiTbService.class);
        lenient().when(tb.bookId()).thenReturn(806);
        lenient().when(tb.lookupBalance(any(UUID.class)))
            .thenReturn(Optional.of(new EmiTbService.Balance(0, 1_000_000, 0, 0)));

        wallet = new Wallet();
        wallet.id = walletId;
        wallet.walletRef = "w-1";
        wallet.personId = UUID.randomUUID();
        wallet.currency = "NAD";
        wallet.status = Wallet.Status.ACTIVE;
        wallet.dailyLimitMinor = new BigDecimal(500000);
        wallet.tbAccountId = Uuid5.walletAccountId(walletId);
        wallet.openedAt = java.time.Instant.now();
        lenient().when(wallets.findById(walletId)).thenReturn(Optional.of(wallet));

        service = new EmiWalletService(wallets, journals, turnover, tb, props(), locks,
            new TransactionTemplate(new StubTxManager()));
    }

    /** Capture-and-return for wallet_journal saves so book() mutates the persisted instance. */
    private void stubJournalInsert() {
        when(journals.save(any(WalletJournal.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static EmiProperties props() {
        return new EmiProperties();
    }

    @Test
    void openWalletCreatesActiveNadWalletWithDeterministicTbAccount() {
        when(wallets.findByWalletRef("w-open")).thenReturn(Optional.empty());
        when(tb.createAccounts(anyList())).thenReturn(new EmiTbResult.Ok());
        when(wallets.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        Wallet w = service.open(UUID.randomUUID(), "w-open");

        assertThat(w.currency).isEqualTo("NAD");
        assertThat(w.status).isEqualTo(Wallet.Status.ACTIVE);
        assertThat(w.tbAccountId).isEqualTo(Uuid5.walletAccountId(w.id));
        verify(tb).createAccounts(argThat(specs -> specs.size() == 2
            && ((List<EmiTbService.AccountSpec>) specs).stream()
                .anyMatch(s -> s.id().equals(Uuid5.floatAccountId()))));
    }

    @Test
    void openWalletReplayedReturnsExistingRowWithoutTouchingTb() {
        Wallet existing = new Wallet();
        existing.id = UUID.randomUUID();
        existing.currency = "NAD";
        when(wallets.findByWalletRef("w-open")).thenReturn(Optional.of(existing));

        Wallet w = service.open(UUID.randomUUID(), "w-open");

        assertThat(w).isSameAs(existing);
        verify(tb, never()).createAccounts(anyList());
    }

    @Test
    void depositBooksExactPairAndMarksBooked() {
        stubJournalInsert();
        when(journals.findByKindAndReference(any(), any())).thenReturn(Optional.empty());
        when(tb.createTransfers(anyList())).thenReturn(new EmiTbResult.Ok());

        WalletJournal j = service.deposit(walletId, 125_00, "STMT-9");

        assertThat(j.state).isEqualTo(WalletJournal.State.BOOKED);
        assertThat(j.tbTransferId).isEqualTo(Uuid5.journalTransferId(j.id));
        verify(tb).createTransfers(argThat(specs -> {
            EmiTbService.TransferSpec s = specs.getFirst();
            return s.debitAccountId().equals(Uuid5.floatAccountId())
                && s.creditAccountId().equals(wallet.tbAccountId)
                && s.amount() == 125_00
                && s.ledger() == 806
                && s.id().equals(j.tbTransferId);
        }));
    }

    @Test
    void depositReplayedBankReferenceNeverDoubleCredits() {
        WalletJournal prior = new WalletJournal();
        prior.id = UUID.randomUUID();
        prior.walletId = walletId;
        prior.kind = WalletJournal.Kind.DEPOSIT;
        prior.amountMinor = new BigDecimal(999_00);
        prior.state = WalletJournal.State.BOOKED;
        when(journals.findByKindAndReference(WalletJournal.Kind.DEPOSIT,
            walletId + ":STMT-5")).thenReturn(Optional.of(prior));

        WalletJournal j = service.deposit(walletId, 999_00, "STMT-5");

        assertThat(j).isSameAs(prior);
        verify(tb, never()).createTransfers(anyList());
    }

    @Test
    void depositTbRejectionMarksJournalFailed() {
        stubJournalInsert();
        when(journals.findByKindAndReference(any(), any())).thenReturn(Optional.empty());
        when(tb.createTransfers(anyList()))
            .thenReturn(new EmiTbResult.DefinitiveRejection("insufficient funds"));

        assertThatThrownBy(() -> service.deposit(walletId, 100, "S-1"))
            .isInstanceOf(ApiException.class).hasMessageContaining("insufficient funds");

        verify(journals, atLeastOnce()).save(argThat(j ->
            WalletJournal.State.FAILED.equals(j.state)));
    }

    @Test
    void depositUnknownTransportLeavesJournalPendingForReplay() {
        stubJournalInsert();
        when(journals.findByKindAndReference(any(), any())).thenReturn(Optional.empty());
        when(tb.createTransfers(anyList()))
            .thenReturn(new EmiTbResult.UnknownTransport("socket died"));

        assertThatThrownBy(() -> service.deposit(walletId, 100, "S-2"))
            .isInstanceOf(ApiException.class);

        verify(journals, atLeastOnce()).save(argThat(j ->
            WalletJournal.State.PENDING.equals(j.state)));
    }

    @Test
    void redeemInsufficientBalanceIsRejectedWithoutTouchingTb() {
        when(tb.lookupBalance(wallet.tbAccountId))
            .thenReturn(Optional.of(new EmiTbService.Balance(0, 50, 0, 0)));

        assertThatThrownBy(() -> service.redeem(walletId, 100))
            .isInstanceOf(ApiException.class).hasMessageContaining("exceeds available");
        verify(tb, never()).createTransfers(anyList());
    }

    @Test
    void redeemBooksDebitWalletCreditFloatAndCountsTurnoverOnlyOnBooked() {
        stubJournalInsert();
        when(tb.createTransfers(anyList())).thenReturn(new EmiTbResult.Ok());
        when(turnover.findByWalletIdAndTurnDate(any(), any())).thenReturn(Optional.empty());
        when(turnover.save(any(WalletDailyTurnover.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletJournal j = service.redeem(walletId, 450_00);

        assertThat(j.state).isEqualTo(WalletJournal.State.BOOKED);
        verify(tb).createTransfers(argThat(specs -> {
            EmiTbService.TransferSpec s = specs.getFirst();
            return s.debitAccountId().equals(wallet.tbAccountId)
                && s.creditAccountId().equals(Uuid5.floatAccountId())
                && s.amount() == 450_00;
        }));
        verify(turnover).save(argThat(t ->
            t.redeemedMinor.longValueExact() == 450_00 && t.turnDate.equals(LocalDate.now(java.time.ZoneOffset.UTC))));
    }

    @Test
    void redeemRejectedByTbDoesNotConsumeDailyCap() {
        stubJournalInsert();
        when(tb.createTransfers(anyList()))
            .thenReturn(new EmiTbResult.DefinitiveRejection("link_transfer_failed"));
        when(turnover.findByWalletIdAndTurnDate(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.redeem(walletId, 100))
            .isInstanceOf(ApiException.class);

        verify(turnover, never()).save(any(WalletDailyTurnover.class));
    }

    @Test
    void redeemOverDailyCapIsBlockedEvenWithBalance() {
        var row = new WalletDailyTurnover();
        row.walletId = walletId;
        row.turnDate = LocalDate.now(java.time.ZoneOffset.UTC);
        row.redeemedMinor = new BigDecimal(450_000);
        when(turnover.findByWalletIdAndTurnDate(any(), any())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.redeem(walletId, 100_000))
            .isInstanceOf(ApiException.class).hasMessageContaining("daily redemption cap");
        verify(tb, never()).createTransfers(anyList());
        verify(journals, never()).save(any(WalletJournal.class));
    }

    @Test
    void frozenWalletBlocksBothMovements() {
        wallet.status = Wallet.Status.FROZEN;

        assertThatThrownBy(() -> service.deposit(walletId, 100, "S-3"))
            .isInstanceOf(ApiException.class).hasMessageContaining("requires ACTIVE");
        assertThatThrownBy(() -> service.redeem(walletId, 100))
            .isInstanceOf(ApiException.class).hasMessageContaining("requires ACTIVE");
        verify(tb, never()).createTransfers(anyList());
    }

    @Test
    void balanceIsCreditMinusDebitFromTb() {
        when(tb.lookupBalance(wallet.tbAccountId))
            .thenReturn(Optional.of(new EmiTbService.Balance(200_00, 1_000_00, 0, 0)));

        assertThat(service.balance(walletId)).isEqualTo(800_00);
    }
}

/** Minimal tx manager so unit tests exercise the same TransactionTemplate code paths. */
class StubTxManager implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
        return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) { /* no-op */ }

    @Override
    public void rollback(TransactionStatus status) { /* no-op */ }
}
