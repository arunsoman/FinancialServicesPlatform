package com.fsp.emi.bill;

import com.fsp.emi.common.ApiException;
import com.fsp.emi.common.Uuid5;
import com.fsp.emi.config.EmiProperties;
import com.fsp.emi.ledger.EmiTbResult;
import com.fsp.emi.ledger.EmiTbService;
import com.fsp.emi.wallet.Wallet;
import com.fsp.emi.wallet.WalletRepository;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 3 — bill-payment saga engine unit tests (TB mocked at the service seam).
 * The ladder under test: HOLD → (submit) → SUBMITTED / VOIDED / PENDING_RECOGNITION,
 * ambiguity doctrine (timeout-after-submit is NEVER a failure), probe disambiguation,
 * four-eyes force-void, TTL sweep and TB hold-expiry reconciliation.
 */
class BillPaymentSagaEngineTest {

    BillPaymentSagaRepository sagas;
    BillerRepository billers;
    WalletRepository wallets;
    SagaEventRepository events;
    ConnectorCallRepository calls;
    BillerGateway gateway;
    EmiTbService tb;
    BillPaymentSagaEngine engine;

    Wallet wallet;
    Biller biller;
    UUID actor = UUID.randomUUID();
    UUID checker = UUID.randomUUID(); // distinct from actor → four-eyes pair

    @BeforeEach
    void setUp() {
        sagas = mock(BillPaymentSagaRepository.class);
        billers = mock(BillerRepository.class);
        wallets = mock(WalletRepository.class);
        events = mock(SagaEventRepository.class);
        calls = mock(ConnectorCallRepository.class);
        gateway = mock(BillerGateway.class);
        tb = mock(EmiTbService.class);

        lenient().when(tb.bookId()).thenReturn(806);
        lenient().when(sagas.save(any(BillPaymentSaga.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(events.save(any(SagaEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(events.findBySagaIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        lenient().when(calls.save(any(ConnectorCall.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(calls.findByKindAndIdempotencyKey(anyString(), anyString())).thenReturn(Optional.empty());
        lenient().when(tb.createAccounts(anyList())).thenReturn(new EmiTbResult.Ok());
        lenient().when(tb.createTransfers(anyList())).thenReturn(new EmiTbResult.Ok());

        wallet = new Wallet();
        wallet.id = UUID.randomUUID();
        wallet.walletRef = "w-pay";
        wallet.personId = actor;
        wallet.currency = "NAD";
        wallet.status = Wallet.Status.ACTIVE;
        wallet.dailyLimitMinor = new BigDecimal(500000);
        wallet.tbAccountId = Uuid5.walletAccountId(wallet.id);
        wallet.openedAt = Instant.now();
        lenient().when(wallets.findById(wallet.id)).thenReturn(Optional.of(wallet));

        biller = new Biller();
        biller.id = UUID.randomUUID();
        biller.code = "CITYPOWER";
        biller.name = "City of Windhoek";
        biller.category = "MUNICIPAL";
        biller.reversalClass = "C";
        biller.transportChannel = Biller.CHANNEL_BILLER_REST;
        biller.endpointCredentialEnvRef = "FSP_BILLER_CITYPOWER_KEY";
        biller.probeSupported = true;
        biller.endpointUrl = "https://citypower.example/api";
        biller.createdAt = Instant.now();
        biller.updatedAt = Instant.now();
        lenient().when(billers.findById(biller.id)).thenReturn(Optional.of(biller));
        lenient().when(billers.findByCode("CITYPOWER")).thenReturn(Optional.of(biller));

        lenient().when(sagas.findByState(anyString(), any())).thenReturn(List.of());
        lenient().when(sagas.findByStateAndExpiresAtBefore(anyString(), any(), any())).thenReturn(List.of());
        lenient().when(sagas.findByStateInAndExpiresAtBefore(anyList(), any(), any())).thenReturn(List.of());

        engine = new BillPaymentSagaEngine(sagas, billers, wallets, events, calls, gateway,
            tb, new EmiProperties(), new com.fsp.emi.common.KeyLocks(),
            new TransactionTemplate(new StubTxManager()));
    }

    private BillPaymentSaga sagaInState(String state) {
        BillPaymentSaga saga = new BillPaymentSaga();
        saga.id = UUID.randomUUID();
        saga.personId = actor;
        saga.walletId = wallet.id;
        saga.currency = "NAD";
        saga.amountMinor = new BigDecimal(12500);
        saga.billerId = biller.id;
        saga.accountRef = "acct-42";
        saga.idempotencyKey = "ik-" + saga.id;
        saga.state = state;
        saga.reversalClass = "C";
        saga.expiresAt = Instant.now().plusSeconds(3000);
        saga.createdAt = Instant.now();
        saga.updatedAt = saga.createdAt;
        lenient().when(sagas.findById(saga.id)).thenReturn(Optional.of(saga));
        return saga;
    }

    // ---------------------------------------------------------------- start

    @Test
    void startPlacesPendingHoldOnDerivedClearingAccount() {
        when(sagas.findByIdempotencyKey("ik-1")).thenReturn(Optional.empty());

        var result = engine.start(actor, wallet.id, "CITYPOWER", "acct-42", 12500, "ik-1");

        assertThat(result.replayed()).isFalse();
        assertThat(result.saga().state).isEqualTo(BillPaymentSaga.State.HOLD);
        assertThat(result.saga().reversalClass).isEqualTo("C");
        assertThat(result.saga().expiresAt).isAfter(Instant.now().plusSeconds(3000));

        @SuppressWarnings("unchecked")
        var hold = ArgumentCaptor.forClass((Class<List<EmiTbService.TransferSpec>>) (Class<?>) List.class);
        verify(tb).createTransfers(hold.capture());
        EmiTbService.TransferSpec spec = hold.getValue().get(0);
        assertThat(spec.id()).isEqualTo(Uuid5.sagaPendingTransferId(result.saga().id));
        assertThat(spec.flags()).isEqualTo(EmiTbService.FLAG_PENDING);
        assertThat(spec.debitAccountId()).isEqualTo(wallet.tbAccountId);
        assertThat(spec.creditAccountId()).isEqualTo(Uuid5.billClearingAccountId("NAD"));
        assertThat(spec.amount()).isEqualTo(12500);
        assertThat(spec.pendingId()).isNull();
        assertThat(spec.timeoutSeconds()).isEqualTo(new EmiProperties().getSagaGraceSeconds());
        // Clearing account ensured before the hold.
        verify(tb).createAccounts(argThat(list -> list.get(0).id()
            .equals(Uuid5.billClearingAccountId("NAD"))));
    }

    @Test
    void startReplaysIdempotencyKeyWithoutTouchingTbOrBiller() {
        BillPaymentSaga existing = sagaInState(BillPaymentSaga.State.HOLD);
        when(sagas.findByIdempotencyKey(existing.idempotencyKey)).thenReturn(Optional.of(existing));

        var result = engine.start(actor, wallet.id, "CITYPOWER", "x", 100, existing.idempotencyKey);

        assertThat(result.replayed()).isTrue();
        assertThat(result.saga()).isSameAs(existing);
        verify(tb, never()).createTransfers(anyList());
    }

    @Test
    void startHoldRejectionLandsVoidedAndThrows() {
        when(sagas.findByIdempotencyKey("ik-2")).thenReturn(Optional.empty());
        when(tb.createTransfers(anyList()))
            .thenReturn(new EmiTbResult.DefinitiveRejection("insufficient_fundsdebit"));

        assertThatThrownBy(() -> engine.start(actor, wallet.id, "CITYPOWER", "a", 100, "ik-2"))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getCode()).isEqualTo("EMI_HOLD_REJECTED"));

        BillPaymentSaga saga = findSavedSaga();
        assertThat(saga.state).isEqualTo(BillPaymentSaga.State.VOIDED);
        assertThat(saga.lastFailureKind).isEqualTo("HOLD_REJECTED");
    }

    @Test
    void startUnknownTransportThrowsRetryableAndKeepsHold() {
        when(sagas.findByIdempotencyKey("ik-3")).thenReturn(Optional.empty());
        when(tb.createTransfers(anyList())).thenReturn(new EmiTbResult.UnknownTransport("conn reset"));

        assertThatThrownBy(() -> engine.start(actor, wallet.id, "CITYPOWER", "a", 100, "ik-3"))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getCode()).isEqualTo("EMI_TB_UNKNOWN"));
        assertThat(findSavedSaga().state).isEqualTo(BillPaymentSaga.State.HOLD);
    }

    // ---------------------------------------------------------------- submit

    @Test
    void submitAcceptedMarksSubmittedAndAcksCall() {
        BillPaymentSaga saga = sagaInState(BillPaymentSaga.State.HOLD);
        when(gateway.submit(any(), any()))
            .thenReturn(new RailResult.Accepted("rail-1", null));
        // Same mutable ConnectorCall instance is saved twice → snapshot state at invocation time.
        var callStateLog = callStates();

        BillPaymentSaga out = engine.submit(saga.id, actor);

        assertThat(out.state).isEqualTo(BillPaymentSaga.State.SUBMITTED);
        assertThat(out.railRef).isEqualTo("rail-1");
        assertThat(out.submittedAt).isNotNull();
        // layer-3 row: save #1 = RECORDED before the hop, save #2 = ACKED after.
        assertThat(callStateLog).containsExactly(ConnectorCall.State.RECORDED, ConnectorCall.State.ACKED);
    }

    @Test
    void submitRejectedVoidsHoldAndThrows() {
        BillPaymentSaga saga = sagaInState(BillPaymentSaga.State.HOLD);
        when(gateway.submit(any(), any()))
            .thenReturn(new RailResult.Rejected("NO_SUCH_ACCOUNT", true));
        var callStateLog = callStates();

        assertThatThrownBy(() -> engine.submit(saga.id, actor))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getCode()).isEqualTo("EMI_BILL_REJECTED"));

        assertThat(saga.state).isEqualTo(BillPaymentSaga.State.VOIDED);
        assertThat(saga.lastFailureKind).isEqualTo("SUBMIT_REJECTED");
        // exactly-once layer 3: recorded before the hop, rejected after
        assertThat(callStateLog).containsExactly(ConnectorCall.State.RECORDED, ConnectorCall.State.REJECTED);
        // the void transfer resolves OUR hold id
        @SuppressWarnings("unchecked")
        var all = ArgumentCaptor.forClass((Class<List<EmiTbService.TransferSpec>>) (Class<?>) List.class);
        verify(tb, org.mockito.Mockito.times(2)).createTransfers(all.capture()); // hold + void
        EmiTbService.TransferSpec voidSpec = all.getAllValues().get(1).get(0);
        assertThat(voidSpec.flags()).isEqualTo(EmiTbService.FLAG_VOID_PENDING);
        assertThat(voidSpec.pendingId()).isEqualTo(Uuid5.sagaPendingTransferId(saga.id));
        assertThat(voidSpec.id()).isEqualTo(Uuid5.sagaVoidTransferId(saga.id));
    }

    @Test
    void submitAmbiguousTransportParksPendingRecognitionAndBlocksPlainVoid() {
        BillPaymentSaga saga = sagaInState(BillPaymentSaga.State.HOLD);
        when(gateway.submit(any(), any())).thenThrow(new RailTransportException(
            new RailResult.FailureKind.CommUnreachable(Duration.ofSeconds(2)), null));

        BillPaymentSaga out = engine.submit(saga.id, actor);

        assertThat(out.state).isEqualTo(BillPaymentSaga.State.PENDING_RECOGNITION);
        // timeout-after-submit is NEVER a failure: plain void is blocked…
        assertThatThrownBy(() -> engine.voidSaga(saga.id, actor, null, "customer changed mind"))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getCode()).isEqualTo("EMI_FORCE_VOID_FOUR_EYES"));
        // …the hold stays in place.
        verify(tb, org.mockito.Mockito.times(1)).createTransfers(anyList());
    }

    @Test
    void submitBreakerOpenOrBackpressureStaysHold() {
        BillPaymentSaga saga = sagaInState(BillPaymentSaga.State.HOLD);
        when(gateway.submit(any(), any()))
            .thenReturn(new RailResult.Failed(new RailResult.FailureKind.CommUnreachable(Duration.ZERO)));

        assertThat(engine.submit(saga.id, actor).state).isEqualTo(BillPaymentSaga.State.HOLD);

        BillPaymentSaga saga2 = sagaInState(BillPaymentSaga.State.HOLD);
        when(gateway.submit(any(), any()))
            .thenReturn(new RailResult.Failed(new RailResult.FailureKind.Backpressure(Duration.ofSeconds(1))));
        BillPaymentSaga out2 = engine.submit(saga2.id, actor);
        assertThat(out2.state).isEqualTo(BillPaymentSaga.State.HOLD);
        assertThat(out2.attempt).isEqualTo(1);
    }

    @Test
    void submitReplaysOnSubmittedAndPendingRecognitionAreNoops() {
        BillPaymentSaga submitted = sagaInState(BillPaymentSaga.State.SUBMITTED);
        assertThat(engine.submit(submitted.id, actor)).isSameAs(submitted);
        BillPaymentSaga pr = sagaInState(BillPaymentSaga.State.PENDING_RECOGNITION);
        assertThat(engine.submit(pr.id, actor)).isSameAs(pr);
        verify(gateway, never()).submit(any(), any());
    }

    // ---------------------------------------------------------------- four-eyes force-void

    @Test
    void forceVoidInPendingRecognitionRequiresTwoDistinctHumans() {
        BillPaymentSaga saga = sagaInState(BillPaymentSaga.State.PENDING_RECOGNITION);

        assertThatThrownBy(() -> engine.voidSaga(saga.id, actor, actor, "self-approval"))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getCode()).isEqualTo("EMI_FORCE_VOID_FOUR_EYES"));
        verify(tb, never()).createTransfers(anyList());

        engine.voidSaga(saga.id, actor, checker, "ambiguous since 35d");
        assertThat(saga.state).isEqualTo(BillPaymentSaga.State.VOIDED);
    }

    @Test
    void holdCancelVoidsWithoutFourEyes() {
        BillPaymentSaga saga = sagaInState(BillPaymentSaga.State.HOLD);

        BillPaymentSaga out = engine.voidSaga(saga.id, actor, null, "customer changed mind");

        assertThat(out.state).isEqualTo(BillPaymentSaga.State.VOIDED);
    }

    @Test
    void submittedAndConfirmedAreVoidBlocked() {
        BillPaymentSaga submitted = sagaInState(BillPaymentSaga.State.SUBMITTED);
        assertThatThrownBy(() -> engine.voidSaga(submitted.id, actor, checker, "x"))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getCode()).isEqualTo("EMI_VOID_BLOCKED"));
        BillPaymentSaga confirmed = sagaInState(BillPaymentSaga.State.CONFIRMED);
        assertThatThrownBy(() -> engine.voidSaga(confirmed.id, actor, checker, "x"))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getCode()).isEqualTo("EMI_VOID_BLOCKED"));
    }

    // ---------------------------------------------------------------- probe + confirm

    @Test
    void probeSettledCommitsPending() {
        BillPaymentSaga saga = sagaInState(BillPaymentSaga.State.PENDING_RECOGNITION);
        saga.railRef = "rail-9";
        when(gateway.probe(any(), any(), any())).thenReturn(Optional.of(new RailResult.Accepted("rail-9", null)));

        BillPaymentSaga out = engine.probeDisambiguate(saga.id, null);

        assertThat(out.state).isEqualTo(BillPaymentSaga.State.CONFIRMED);
        assertThat(out.settledAt).isNotNull();
        @SuppressWarnings("unchecked")
        var all = ArgumentCaptor.forClass((Class<List<EmiTbService.TransferSpec>>) (Class<?>) List.class);
        verify(tb).createTransfers(all.capture());
        EmiTbService.TransferSpec commit = all.getValue().get(0);
        assertThat(commit.flags()).isEqualTo(EmiTbService.FLAG_POST_PENDING);
        assertThat(commit.pendingId()).isEqualTo(Uuid5.sagaPendingTransferId(saga.id));
        assertThat(commit.id()).isEqualTo(Uuid5.sagaCommitTransferId(saga.id));
        assertThat(commit.debitAccountId()).isEqualTo(wallet.tbAccountId);
        assertThat(commit.amount()).isEqualTo(12500);
    }

    @Test
    void probeNotFoundVoidsHold() {
        BillPaymentSaga saga = sagaInState(BillPaymentSaga.State.SUBMITTED);
        saga.railRef = "rail-10";
        when(gateway.probe(any(), any(), any()))
            .thenReturn(Optional.of(new RailResult.Rejected("NOT_FOUND", true)));

        BillPaymentSaga out = engine.probeDisambiguate(saga.id, null);

        assertThat(out.state).isEqualTo(BillPaymentSaga.State.VOIDED);
    }

    @Test
    void probeInconclusiveChangesNothingAndSweeperProbesAmbiguated() {
        BillPaymentSaga saga = sagaInState(BillPaymentSaga.State.PENDING_RECOGNITION);
        saga.railRef = "rail-11";
        when(gateway.probe(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(engine.probeDisambiguate(saga.id, null).state)
            .isEqualTo(BillPaymentSaga.State.PENDING_RECOGNITION);

        // no probes without a rail ref-capable state, machine actor allowed
        verify(events).save(argThat(e -> "PROBE_INCONCLUSIVE".equals(e.eventType)));
    }

    // ---------------------------------------------------------------- TB hold expiry

    @Test
    void confirmAfterTbHoldExpiredMarksExpiredWithFundsReleased() {
        BillPaymentSaga saga = sagaInState(BillPaymentSaga.State.SUBMITTED);
        saga.railRef = "rail-12";
        when(tb.createTransfers(anyList()))
            .thenReturn(new EmiTbResult.DefinitiveRejection(
                "index=0 status=pending_transfer_expired"));

        BillPaymentSaga out = engine.confirm(saga.id, null, "recon mark-done");

        assertThat(out.state).isEqualTo(BillPaymentSaga.State.EXPIRED);
        assertThat(out.lastFailureKind).isEqualTo("TB_HOLD_EXPIRED");
    }

    // ---------------------------------------------------------------- TTL sweep

    @Test
    void sweepVoidsExpiredHoldsAndAmbiguatesExpiredSubmitted() {
        BillPaymentSaga hold = sagaInState(BillPaymentSaga.State.HOLD);
        hold.expiresAt = Instant.now().minusSeconds(60);
        BillPaymentSaga submitted = sagaInState(BillPaymentSaga.State.SUBMITTED);
        submitted.expiresAt = Instant.now().minusSeconds(60);

        when(sagas.findByStateAndExpiresAtBefore(org.mockito.ArgumentMatchers.eq(BillPaymentSaga.State.HOLD),
            any(Instant.class), any())).thenReturn(List.of(hold));
        when(sagas.findByStateAndExpiresAtBefore(org.mockito.ArgumentMatchers.eq(BillPaymentSaga.State.SUBMITTED),
            any(Instant.class), any())).thenReturn(List.of(submitted));

        var summary = engine.sweepExpired();

        assertThat(summary.holdExpiriesVoided()).isEqualTo(1);
        assertThat(summary.submittedExpired()).isEqualTo(1);
        assertThat(hold.state).isEqualTo(BillPaymentSaga.State.VOIDED);
        assertThat(submitted.state).isEqualTo(BillPaymentSaga.State.PENDING_RECOGNITION);
        verify(events).save(argThat(e -> "TTL_EXPIRE_VOID".equals(e.eventType)));
        verify(events).save(argThat(e -> "TTL_AWAIT_RECON".equals(e.eventType)));
        // the expired SUBMITTED saga is NEVER auto-voided (ambiguity doctrine)
        verify(tb, org.mockito.Mockito.times(1)).createTransfers(anyList());
    }

    // ---------------------------------------------------------------- helpers

    /** The engine mutates the fixture in place; grab the last persisted instance. */
    private BillPaymentSaga findSavedSaga() {
        ArgumentCaptor<BillPaymentSaga> cap = ArgumentCaptor.forClass(BillPaymentSaga.class);
        verify(sagas, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        return cap.getValue();
    }

    /** Invocation-time state snapshots of every ConnectorCall save (same instance is re-saved). */
    private java.util.List<String> callStates() {
        var states = new java.util.concurrent.CopyOnWriteArrayList<String>();
        org.mockito.Mockito.when(calls.save(any(ConnectorCall.class))).thenAnswer(inv -> {
            ConnectorCall c = inv.getArgument(0);
            states.add(c.state);
            return c;
        });
        return states;
    }

    /** Mirrors EmiWalletServiceTest: commits are no-ops in unit scope. */
    static class StubTxManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException { /* no-op */ }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException { /* no-op */ }
    }
}
