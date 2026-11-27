package com.fsp.psp.acquire;

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
import com.fsp.psp.settlement.ClearingPosition;
import com.fsp.psp.settlement.ClearingPositionRepository;
import com.fsp.shared.rails.RailResult;
import com.fsp.shared.rails.RailTransportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 5 — merchant-acquiring saga tests (plan §11). TB is mocked at the PspTbService
 * seam (test convention). The capture under test: a LINKED pending chain (EMI doctrine —
 * all but the LAST leg carry LINKED; DR settlement-float gross → CR clearing; DR clearing
 * fee → CR fee-revenue) resolved atomically at sweep, with the clearing position row kept
 * as the bookkeeping mirror.
 */
class PspAcquireServiceTest {

    AcquisitionRepository acquisitions;
    RailCallRepository calls;
    MerchantService merchants;
    ConnectorService connectors;
    AcquirerRestGateway restGateway;
    AcquirerDemoGateway demoGateway;
    PspTbService tb;
    PspEventRepository events;
    PspOutboxRepository outbox;
    ClearingPositionRepository positions;
    PspAcquireServiceTestHarness harness;
    AcquireService service;

    final UUID tenantId = UUID.fromString("11111111-2222-4333-8444-555555555555");
    final UUID merchantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        acquisitions = mock(AcquisitionRepository.class);
        calls = mock(RailCallRepository.class);
        merchants = mock(MerchantService.class);
        connectors = mock(ConnectorService.class);
        restGateway = mock(AcquirerRestGateway.class);
        demoGateway = mock(AcquirerDemoGateway.class);
        tb = mock(PspTbService.class);
        events = mock(PspEventRepository.class);
        outbox = mock(PspOutboxRepository.class);
        positions = mock(ClearingPositionRepository.class);
        when(positions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(positions.findByConnectorCodeAndCurrencyAndPositionDate(any(), any(), any()))
            .thenReturn(java.util.Optional.empty());

        TransactionTemplate tx = new TransactionTemplate(new StubTxManager());
        service = new AcquireService(acquisitions, calls, merchants, connectors,
            restGateway, demoGateway, tb, events, outbox, positions, new PspProperties(),
            new KeyLocks(), tx);
        harness = new PspAcquireServiceTestHarness(acquisitions);

        when(merchants.byId(tenantId, merchantId)).thenReturn(activeMerchant());
        when(connectors.enabledByCode("DEMO-BANK")).thenReturn(connector("DEMO"));
        when(acquisitions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(calls.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(calls.findByCallKey(any())).thenReturn(java.util.Optional.empty());
    }

    Merchant activeMerchant() {
        Merchant m = new Merchant();
        m.id = merchantId;
        m.tenantId = tenantId;
        m.merchantCode = "demo-merchant";
        m.displayName = "Demo";
        m.status = Merchant.STATUS_ACTIVE;
        m.createdAt = Instant.now();
        m.updatedAt = m.createdAt;
        return m;
    }

    BankConnector connector(String format) {
        BankConnector c = new BankConnector();
        c.id = UUID.randomUUID();
        c.bankCode = "DEMO-BANK";
        c.bankName = "Demo";
        c.railFamily = "CARD_SCHEME";
        c.transportChannel = "REST";
        c.messageFormat = format;
        c.endpointCredentialEnvRef = "FSP_PSP_DEMO_ENDPOINT";
        c.profileStatus = BankConnector.STATUS_ENABLED;
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        return c;
    }

    Acquisition newAcq(String state) {
        Acquisition a = new Acquisition();
        a.id = UUID.randomUUID();
        a.merchantId = merchantId;
        a.tenantId = tenantId;
        a.connectorCode = "DEMO-BANK";
        a.currency = "NAD";
        a.grossMinor = new BigDecimal(3000);
        a.feeMinor = new BigDecimal(300);
        a.netMinor = new BigDecimal(2700);
        a.pspRef = "ORD-1";
        a.state = state;
        a.idempotencyKey = "idem-" + UUID.randomUUID();
        a.createdAt = Instant.now();
        a.updatedAt = a.createdAt;
        return a;
    }

    // ------------------------------------------------------------------ validation

    @Test
    void currencyUnsupported() {
        assertThatThrownBy(() -> service.authorize(tenantId, merchantId, "DEMO-BANK",
                "ORD-1", 100, 0, "USD", "k1"))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("PSP_CURRENCY_UNSUPPORTED"));
    }

    @Test
    void amountsInvalidAndCapped() {
        assertThatThrownBy(() -> service.authorize(tenantId, merchantId, "DEMO-BANK",
                "ORD-1", 0, 0, "NAD", "k1"))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("PSP_AMOUNT_INVALID"));
        assertThatThrownBy(() -> service.authorize(tenantId, merchantId, "DEMO-BANK",
                "ORD-1", 100, 101, "NAD", "k1"))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("PSP_FEE_INVALID"));
        assertThatThrownBy(() -> service.authorize(tenantId, merchantId, "DEMO-BANK",
                "ORD-1", 1_000_000_000L, 0, "NAD", "k1"))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("PSP_AMOUNT_CAP"));
    }

    @Test
    void idempotencyKeyRequired() {
        assertThatThrownBy(() -> service.authorize(tenantId, merchantId, "DEMO-BANK",
                "ORD-1", 100, 0, "NAD", " "))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("PSP_IDEMPOTENCY_REQUIRED"));
    }

    @Test
    void replayReturnsSameSagaUntouched() {
        Acquisition existing = newAcq(Acquisition.STATE_AUTHORIZED);
        when(acquisitions.findByIdempotencyKey(existing.idempotencyKey))
            .thenReturn(java.util.Optional.of(existing));
        var r = service.authorize(tenantId, merchantId, "DEMO-BANK", existing.pspRef,
            existing.gross(), existing.fee(), "NAD", existing.idempotencyKey);
        assertThat(r.replay()).isTrue();
        assertThat(r.acquisition().id).isEqualTo(existing.id);
        verify(restGateway, never()).authorize(any(), any());
        verify(demoGateway, never()).authorize(any(), any());
    }

    @Test
    void idempotencyDriftRejected() {
        Acquisition existing = newAcq(Acquisition.STATE_AUTHORIZED);
        when(acquisitions.findByIdempotencyKey(existing.idempotencyKey))
            .thenReturn(java.util.Optional.of(existing));
        assertThatThrownBy(() -> service.authorize(tenantId, merchantId, "DEMO-BANK",
                existing.pspRef, existing.gross() + 1, existing.fee(), "NAD",
                existing.idempotencyKey))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("PSP_IDEMPOTENCY_DRIFT"));
    }

    // ------------------------------------------------------------------ rail dispositions

    @Test
    void authorizeAcceptedStoresRailRefAndEmitsOutbox() {
        when(demoGateway.authorize(any(), any())).thenReturn(
            new RailResult.Accepted("demo-ACCEPTED-1", Instant.now()));
        var r = service.authorize(tenantId, merchantId, "DEMO-BANK", "ORD-1",
            3000, 300, "NAD", "idem-1");
        assertThat(r.replay()).isFalse();
        Acquisition saved = harness.savedAcquisition();
        assertThat(saved.state).isEqualTo(Acquisition.STATE_AUTHORIZED);
        assertThat(saved.railRef).isEqualTo("demo-ACCEPTED-1");
        verify(outbox, atLeastOnce()).save(any());
        verify(events, atLeastOnce()).save(any());
        // exactly-once rail ledger row recorded + ACKed
        ArgumentCaptor<RailCall> callCap = ArgumentCaptor.forClass(RailCall.class);
        verify(calls, atLeastOnce()).save(callCap.capture());
        assertThat(callCap.getValue().state).isEqualTo(RailCall.STATE_ACKED);
    }

    @Test
    void railRejectionIsBusinessTerminal() {
        when(demoGateway.authorize(any(), any())).thenReturn(
            new RailResult.Rejected("REFUSED", true));
        service.authorize(tenantId, merchantId, "DEMO-BANK", "ORD-FAIL",
            3000, 300, "NAD", "idem-r");
        assertThat(harness.savedAcquisition().state).isEqualTo(Acquisition.STATE_REJECTED);
        assertThat(harness.savedAcquisition().failureCode).isEqualTo("REFUSED");
        verify(outbox, atLeastOnce()).save(any());
    }

    @Test
    void backpressureKeepsCreatedDeferred() {
        when(demoGateway.authorize(any(), any())).thenReturn(
            new RailResult.Failed(new RailResult.FailureKind.Backpressure(java.time.Duration.ofSeconds(5))));
        var r = service.authorize(tenantId, merchantId, "DEMO-BANK", "ORD-DEFER",
            3000, 300, "NAD", "idem-defer");
        assertThat(r.acquisition().state).isEqualTo(Acquisition.STATE_CREATED);
        assertThat(harness.savedAcquisition().state).isEqualTo(Acquisition.STATE_CREATED);
    }

    @Test
    void unknownTransportStaysCreated() {
        when(demoGateway.authorize(any(), any())).thenThrow(new RailTransportException(
            new RailResult.FailureKind.CommUnreachable(java.time.Duration.ofSeconds(1)), null));
        var r = service.authorize(tenantId, merchantId, "DEMO-BANK", "ORD-UNK",
            3000, 300, "NAD", "idem-unk");
        assertThat(r.acquisition().state).isEqualTo(Acquisition.STATE_CREATED);
    }

    // ------------------------------------------------------------------ capture

    @Test
    void captureBooksLinkedPendingChain() {
        Acquisition a = newAcq(Acquisition.STATE_AUTHORIZED);
        when(acquisitions.findById(a.id)).thenReturn(java.util.Optional.of(a));
        when(acquisitions.findByIdAndTenantId(a.id, tenantId)).thenReturn(java.util.Optional.of(a));
        when(tb.createAccounts(anyList())).thenReturn(new PspTbResult.Ok());
        when(tb.createTransfers(anyList())).thenReturn(new PspTbResult.Ok());
        when(tb.bookId()).thenReturn(805);

        Acquisition result = service.capture(tenantId, a.id);
        assertThat(result.state).isEqualTo(Acquisition.STATE_CAPTURED);
        assertThat(result.cycleDate).isEqualTo(LocalDate.now(java.time.ZoneOffset.UTC));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PspTbService.TransferSpec>> cap = ArgumentCaptor.forClass(List.class);
        verify(tb).createTransfers(cap.capture());
        List<PspTbService.TransferSpec> legs = cap.getValue();
        assertThat(legs).hasSize(2);
        assertThat(legs.get(0).id()).isEqualTo(PspUuid5.captureLegTransferId(a.id, 0));
        assertThat(legs.get(0).debitAccountId())
            .isEqualTo(PspUuid5.settlementFloatAccountId("NAD"));
        assertThat(legs.get(0).creditAccountId())
            .isEqualTo(PspUuid5.clearingAccountId("DEMO-BANK", "NAD"));
        assertThat(legs.get(0).amount()).isEqualTo(3000);
        assertThat(legs.get(0).flags() & PspTbService.FLAG_PENDING).isNotZero();
        assertThat(legs.get(0).flags() & PspTbService.FLAG_LINKED).isNotZero(); // non-last leg
        assertThat(legs.get(0).timeoutSeconds()).isPositive();
        // fee leg: DR clearing → CR fee revenue; LAST leg terminates the chain (no LINKED)
        assertThat(legs.get(1).debitAccountId())
            .isEqualTo(PspUuid5.clearingAccountId("DEMO-BANK", "NAD"));
        assertThat(legs.get(1).creditAccountId()).isEqualTo(PspUuid5.feeRevenueAccountId("NAD"));
        assertThat(legs.get(1).amount()).isEqualTo(300);
        assertThat(legs.get(1).flags()).isEqualTo(PspTbService.FLAG_PENDING);
        // bookkeeping mirror: position row created with the capture gross
        ArgumentCaptor<ClearingPosition> posCap = ArgumentCaptor.forClass(ClearingPosition.class);
        verify(positions, atLeastOnce()).save(posCap.capture());
        assertThat(posCap.getValue().amountMinor.longValue()).isEqualTo(3000);
    }

    @Test
    void zeroFeeCaptureIsSingleLeg() {
        Acquisition a = newAcq(Acquisition.STATE_AUTHORIZED);
        a.feeMinor = BigDecimal.ZERO;
        a.netMinor = a.grossMinor;
        when(acquisitions.findById(a.id)).thenReturn(java.util.Optional.of(a));
        when(acquisitions.findByIdAndTenantId(a.id, tenantId)).thenReturn(java.util.Optional.of(a));
        when(tb.createAccounts(anyList())).thenReturn(new PspTbResult.Ok());
        when(tb.createTransfers(anyList())).thenReturn(new PspTbResult.Ok());

        service.capture(tenantId, a.id);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PspTbService.TransferSpec>> cap = ArgumentCaptor.forClass(List.class);
        verify(tb).createTransfers(cap.capture());
        assertThat(cap.getValue()).hasSize(1);
        assertThat(cap.getValue().get(0).flags()).isEqualTo(PspTbService.FLAG_PENDING);
    }

    @Test
    void captureAccruesExistingPosition() {
        Acquisition a = newAcq(Acquisition.STATE_AUTHORIZED);
        ClearingPosition existing = new ClearingPosition();
        existing.amountMinor = new BigDecimal(1000);
        existing.currency = "NAD";
        when(positions.findByConnectorCodeAndCurrencyAndPositionDate(
                "DEMO-BANK", "NAD", LocalDate.now(java.time.ZoneOffset.UTC)))
            .thenReturn(java.util.Optional.of(existing));
        when(acquisitions.findById(a.id)).thenReturn(java.util.Optional.of(a));
        when(acquisitions.findByIdAndTenantId(a.id, tenantId)).thenReturn(java.util.Optional.of(a));
        when(tb.createAccounts(anyList())).thenReturn(new PspTbResult.Ok());
        when(tb.createTransfers(anyList())).thenReturn(new PspTbResult.Ok());

        service.capture(tenantId, a.id);
        ArgumentCaptor<ClearingPosition> posCap = ArgumentCaptor.forClass(ClearingPosition.class);
        verify(positions, atLeastOnce()).save(posCap.capture());
        assertThat(posCap.getValue().amountMinor.longValue()).isEqualTo(4000);
    }

    @Test
    void captureReplayIsNoOp() {
        Acquisition a = newAcq(Acquisition.STATE_CAPTURED);
        a.cycleDate = LocalDate.now(java.time.ZoneOffset.UTC);
        when(acquisitions.findById(a.id)).thenReturn(java.util.Optional.of(a));
        when(acquisitions.findByIdAndTenantId(a.id, tenantId)).thenReturn(java.util.Optional.of(a));
        service.capture(tenantId, a.id);
        verify(tb, never()).createTransfers(anyList());
    }

    @Test
    void captureOfWrongStateRejected() {
        Acquisition a = newAcq(Acquisition.STATE_CREATED);
        when(acquisitions.findById(a.id)).thenReturn(java.util.Optional.of(a));
        when(acquisitions.findByIdAndTenantId(a.id, tenantId)).thenReturn(java.util.Optional.of(a));
        assertThatThrownBy(() -> service.capture(tenantId, a.id))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("PSP_ACQ_STATE"));
    }

    @Test
    void tbRejectionSurfaces409() {
        Acquisition a = newAcq(Acquisition.STATE_AUTHORIZED);
        when(acquisitions.findById(a.id)).thenReturn(java.util.Optional.of(a));
        when(acquisitions.findByIdAndTenantId(a.id, tenantId)).thenReturn(java.util.Optional.of(a));
        when(tb.createAccounts(anyList())).thenReturn(new PspTbResult.Ok());
        when(tb.createTransfers(anyList())).thenReturn(
            new PspTbResult.DefinitiveRejection("credits_must_not_exceed_debits"));
        when(tb.bookId()).thenReturn(805);
        assertThatThrownBy(() -> service.capture(tenantId, a.id))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("PSP_TB_REJECTED"));
    }

    @Test
    void tbUnknownSurfaces503() {
        Acquisition a = newAcq(Acquisition.STATE_AUTHORIZED);
        when(acquisitions.findById(a.id)).thenReturn(java.util.Optional.of(a));
        when(acquisitions.findByIdAndTenantId(a.id, tenantId)).thenReturn(java.util.Optional.of(a));
        when(tb.createAccounts(anyList())).thenReturn(new PspTbResult.Ok());
        when(tb.createTransfers(anyList())).thenReturn(
            new PspTbResult.UnknownTransport("timeout"));
        when(tb.bookId()).thenReturn(805);
        assertThatThrownBy(() -> service.capture(tenantId, a.id))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("PSP_TB_UNKNOWN"));
    }

    // ------------------------------------------------------------------ void

    @Test
    void voidOfCreatedFlipsDirectly() {
        Acquisition a = newAcq(Acquisition.STATE_CREATED);
        when(acquisitions.findById(a.id)).thenReturn(java.util.Optional.of(a));
        when(acquisitions.findByIdAndTenantId(a.id, tenantId)).thenReturn(java.util.Optional.of(a));
        Acquisition result = service.voidAcquisition(tenantId, a.id);
        assertThat(result.state).isEqualTo(Acquisition.STATE_VOIDED);
        verify(tb, never()).createTransfers(anyList()); // no money ever moved
    }

    @Test
    void voidOfCapturedReleasesPendingChain() {
        Acquisition a = newAcq(Acquisition.STATE_CAPTURED);
        when(acquisitions.findById(a.id)).thenReturn(java.util.Optional.of(a));
        when(acquisitions.findByIdAndTenantId(a.id, tenantId)).thenReturn(java.util.Optional.of(a));
        when(tb.createAccounts(anyList())).thenReturn(new PspTbResult.Ok());
        when(tb.createTransfers(anyList())).thenReturn(new PspTbResult.Ok());
        when(tb.bookId()).thenReturn(805);
        Acquisition result = service.voidAcquisition(tenantId, a.id);
        assertThat(result.state).isEqualTo(Acquisition.STATE_VOIDED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PspTbService.TransferSpec>> cap = ArgumentCaptor.forClass(List.class);
        verify(tb).createTransfers(cap.capture());
        for (PspTbService.TransferSpec spec : cap.getValue()) {
            assertThat(spec.flags() & PspTbService.FLAG_VOID_PENDING).isNotZero();
            assertThat(spec.pendingId()).isNotNull();
        }
    }

    @Test
    void voidOfSettledRefused() {
        Acquisition a = newAcq(Acquisition.STATE_SETTLED);
        when(acquisitions.findById(a.id)).thenReturn(java.util.Optional.of(a));
        when(acquisitions.findByIdAndTenantId(a.id, tenantId)).thenReturn(java.util.Optional.of(a));
        assertThatThrownBy(() -> service.voidAcquisition(tenantId, a.id))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("PSP_ACQ_COMMITTED"));
    }

    // ------------------------------------------------------------------ harness

    static class PspAcquireServiceTestHarness {
        private final AcquisitionRepository acquisitions;
        private Acquisition last;

        PspAcquireServiceTestHarness(AcquisitionRepository acquisitions) {
            this.acquisitions = acquisitions;
        }

        /** Latest saved acquisition row (mutated in-place by the engine). */
        Acquisition savedAcquisition() {
            ArgumentCaptor<Acquisition> cap = ArgumentCaptor.forClass(Acquisition.class);
            verify(acquisitions, atLeastOnce()).save(cap.capture());
            last = cap.getValue();
            return last;
        }
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
