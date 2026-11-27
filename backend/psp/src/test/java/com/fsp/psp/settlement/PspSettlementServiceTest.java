package com.fsp.psp.settlement;

import com.fsp.psp.acquire.AcquireService;
import com.fsp.psp.acquire.Acquisition;
import com.fsp.psp.acquire.AcquisitionRepository;
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
import com.fsp.psp.outbox.PspOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 5 — settlement sweep tests (plan §11): the commit chain resolves at sweep, the
 * closing_debit DR clearing → CR merchant payable drains clearing to ZERO (hard invariant
 * asserted per batch close), and the batch row is the exactly-once claim. TB is mocked at
 * the PspTbService seam (test convention).
 */
class PspSettlementServiceTest {

    SettlementBatchRepository batches;
    AcquisitionRepository acquisitions;
    ClearingPositionRepository positions;
    MerchantPayableRepository payables;
    MerchantService merchants;
    ConnectorService connectors;
    AcquireService acquireService;
    PspTbService tb;
    PspEventRepository events;
    PspOutboxRepository outbox;
    SettlementService service;

    final UUID tenantId = UUID.fromString("11111111-2222-4333-8444-555555555555");
    final UUID merchantId = UUID.randomUUID();
    final UUID acqId = UUID.randomUUID();
    LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);

    @BeforeEach
    void setUp() {
        batches = mock(SettlementBatchRepository.class);
        acquisitions = mock(AcquisitionRepository.class);
        positions = mock(ClearingPositionRepository.class);
        payables = mock(MerchantPayableRepository.class);
        merchants = mock(MerchantService.class);
        connectors = mock(ConnectorService.class);
        acquireService = mock(AcquireService.class);
        tb = mock(PspTbService.class);
        events = mock(PspEventRepository.class);
        outbox = mock(PspOutboxRepository.class);
        PspProperties props = new PspProperties();
        TransactionTemplate tx = new TransactionTemplate(new StubTxManager());
        service = new SettlementService(batches, acquisitions, positions, payables,
            merchants, connectors, acquireService, tb, events, outbox, props,
            new KeyLocks(), tx);

        when(connectors.enabledByCode("DEMO-BANK")).thenReturn(enabledConnector());
        when(merchants.byId(tenantId, merchantId)).thenReturn(merchant());
        when(batches.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(positions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(payables.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(events.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outbox.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(acquireService.commitLegSpecs(any())).thenReturn(List.of());
        lenient().when(acquisitions.findById(acqId)).thenReturn(Optional.of(capturedAcq()));
        lenient().when(tb.lookupBalance(any()))
            .thenReturn(Optional.of(new PspTbService.Balance(0, 0, 0, 0)));
    }

    BankConnector enabledConnector() {
        BankConnector c = new BankConnector();
        c.bankCode = "DEMO-BANK";
        c.profileStatus = BankConnector.STATUS_ENABLED;
        return c;
    }

    Merchant merchant() {
        Merchant m = new Merchant();
        m.id = merchantId;
        m.tenantId = tenantId;
        m.merchantCode = "demo-merchant";
        m.status = Merchant.STATUS_ACTIVE;
        return m;
    }

    Acquisition capturedAcq() {
        Acquisition a = new Acquisition();
        a.id = acqId;
        a.merchantId = merchantId;
        a.tenantId = tenantId;
        a.connectorCode = "DEMO-BANK";
        a.currency = "NAD";
        a.grossMinor = new BigDecimal(3000);
        a.feeMinor = new BigDecimal(300);
        a.netMinor = new BigDecimal(2700);
        a.pspRef = "ORD-1";
        a.state = Acquisition.STATE_CAPTURED;
        a.cycleDate = yesterday;
        return a;
    }

    /** Position row holding exactly the parked gross for the cycle (as capture created it). */
    ClearingPosition openPosition() {
        ClearingPosition p = new ClearingPosition();
        p.id = UUID.randomUUID();
        p.connectorCode = "DEMO-BANK";
        p.currency = "NAD";
        p.positionDate = yesterday;
        p.tbAccountId = PspUuid5.clearingAccountId("DEMO-BANK", "NAD");
        p.amountMinor = new BigDecimal(3000);
        p.settled = false;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        return p;
    }

    void stubSweepable() {
        when(acquisitions.sweepable(eq("DEMO-BANK"), eq("NAD"), eq(yesterday),
                any(PageRequest.class)))
            .thenReturn(List.of(capturedAcq()));
        lenient().when(acquisitions.findById(acqId)).thenReturn(Optional.of(capturedAcq()));
        when(tb.createTransfers(anyList())).thenReturn(new PspTbResult.Ok());
    }

    @Test
    void sweepCommitsClosesAndDrainsClearing() {
        when(batches.findByConnectorCodeAndCurrencyAndWindowStartAndWindowEnd(
                eq("DEMO-BANK"), eq("NAD"), any(Instant.class), any(Instant.class)))
            .thenReturn(Optional.empty());
        stubSweepable();
        when(positions.findByConnectorCodeAndCurrencyAndPositionDate("DEMO-BANK", "NAD", yesterday))
            .thenReturn(Optional.of(openPosition()));

        var r = service.sweep("DEMO-BANK", "NAD", yesterday);

        assertThat(r.state()).isEqualTo("COMPLETED");
        assertThat(r.settledCount()).isEqualTo(1);
        assertThat(r.replay()).isFalse();

        // Two TB calls: (1) commit chain — pending post-pending legs, (2) closing_debit.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PspTbService.TransferSpec>> cap = ArgumentCaptor.forClass(List.class);
        verify(tb, times(2)).createTransfers(cap.capture());
        List<PspTbService.TransferSpec> close = cap.getAllValues().get(1);
        assertThat(close).hasSize(1);
        assertThat(close.get(0).id()).isEqualTo(PspUuid5.closingDebitTransferId(acqId));
        assertThat(close.get(0).debitAccountId())
            .isEqualTo(PspUuid5.clearingAccountId("DEMO-BANK", "NAD"));
        assertThat(close.get(0).creditAccountId())
            .isEqualTo(PspUuid5.merchantPayableAccountId(merchantId, "NAD"));
        assertThat(close.get(0).amount()).isEqualTo(2700);
        assertThat(close.get(0).code()).isEqualTo(503); // CODE_CLOSING_DEBIT
        assertThat(close.get(0).flags()).isZero();
        assertThat(close.get(0).pendingId()).isNull();
        assertThat(close.get(0).timeoutSeconds()).isZero();

        // acquisition row SETTLED
        ArgumentCaptor<Acquisition> acqCap = ArgumentCaptor.forClass(Acquisition.class);
        verify(acquisitions, atLeastOnce()).save(acqCap.capture());
        assertThat(acqCap.getValue().state).isEqualTo(Acquisition.STATE_SETTLED);

        // position drained to zero → settled flag
        ArgumentCaptor<ClearingPosition> posCap = ArgumentCaptor.forClass(ClearingPosition.class);
        verify(positions, atLeastOnce()).save(posCap.capture());
        assertThat(posCap.getValue().amountMinor.longValue()).isZero();
        assertThat(posCap.getValue().settled).isTrue();

        // payable accrued for the settlement cycle day
        ArgumentCaptor<MerchantPayable> payCap = ArgumentCaptor.forClass(MerchantPayable.class);
        verify(payables, atLeastOnce()).save(payCap.capture());
        assertThat(payCap.getValue().accrualMinor.longValue()).isEqualTo(2700);
        assertThat(payCap.getValue().periodStart).isEqualTo(yesterday);

        verify(outbox, atLeastOnce()).save(any());
    }

    @Test
    void openCycleRefused() {
        assertThatThrownBy(() -> service.sweep("DEMO-BANK", "NAD", LocalDate.now(ZoneOffset.UTC)))
            .extracting(e -> ((ApiException) e).getCode()).isEqualTo("PSP_CYCLE_OPEN");
        verify(tb, never()).createTransfers(anyList());
    }

    @Test
    void completedBatchReplaysWithoutRebooking() {
        SettlementBatch b = new SettlementBatch();
        b.id = UUID.randomUUID();
        b.connectorCode = "DEMO-BANK";
        b.currency = "NAD";
        b.windowStart = yesterday.atStartOfDay(ZoneOffset.UTC).toInstant();
        b.windowEnd = yesterday.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        b.state = SettlementBatch.STATE_COMPLETED;
        b.createdAt = Instant.now();
        b.updatedAt = b.createdAt;
        when(batches.findByConnectorCodeAndCurrencyAndWindowStartAndWindowEnd(
                eq("DEMO-BANK"), eq("NAD"), any(Instant.class), any(Instant.class)))
            .thenReturn(Optional.of(b));
        when(acquisitions.countByConnectorCodeAndCurrencyAndCycleDateAndState(
                "DEMO-BANK", "NAD", yesterday, Acquisition.STATE_SETTLED)).thenReturn(1L);
        // drained position: amount 0, settled
        ClearingPosition drained = openPosition();
        drained.amountMinor = BigDecimal.ZERO;
        drained.settled = true;
        when(positions.findByConnectorCodeAndCurrencyAndPositionDate("DEMO-BANK", "NAD", yesterday))
            .thenReturn(Optional.of(drained));

        var r = service.sweep("DEMO-BANK", "NAD", yesterday);

        assertThat(r.replay()).isTrue();
        assertThat(r.settledCount()).isEqualTo(1);
        assertThat(r.settledNetMinor()).isZero();
        verify(tb, never()).createTransfers(anyList());
        verify(acquisitions, never()).save(any());
    }

    @Test
    void pendingExpiryRepairsToVoided() {
        when(batches.findByConnectorCodeAndCurrencyAndWindowStartAndWindowEnd(
                eq("DEMO-BANK"), eq("NAD"), any(Instant.class), any(Instant.class)))
            .thenReturn(Optional.empty());
        stubSweepable();
        when(tb.createTransfers(anyList())).thenReturn(new PspTbResult.DefinitiveRejection(
            "pending_transfer_expired: cannot reference expired pending transfer"));
        // repair path only saves the acquisition; position lookup inside close is empty-safe
        lenient().when(positions.findByConnectorCodeAndCurrencyAndPositionDate(
                "DEMO-BANK", "NAD", yesterday)).thenReturn(Optional.empty());
        lenient().when(tb.lookupBalance(any())).thenReturn(Optional.empty());

        var r = service.sweep("DEMO-BANK", "NAD", yesterday);

        // doctrine R2/R3: timeout is NOT failure — batch completes, saga repairs to VOIDED
        assertThat(r.state()).isEqualTo("COMPLETED");
        assertThat(r.settledCount()).isZero();
        ArgumentCaptor<Acquisition> acqCap = ArgumentCaptor.forClass(Acquisition.class);
        verify(acquisitions, atLeastOnce()).save(acqCap.capture());
        assertThat(acqCap.getValue().state).isEqualTo(Acquisition.STATE_VOIDED);
        assertThat(acqCap.getValue().failureCode).isEqualTo("PSP_CAPTURE_TIMEOUT");
    }

    static class StubTxManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition)
                throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) { /* no-op */ }

        @Override
        public void rollback(TransactionStatus status) { /* no-op */ }
    }
}
