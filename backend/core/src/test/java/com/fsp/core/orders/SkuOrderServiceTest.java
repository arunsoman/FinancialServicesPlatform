package com.fsp.core.orders;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.KeyLocks;
import com.fsp.core.journal.JournalDtos;
import com.fsp.core.journal.JournalService;
import com.fsp.core.ledger.GlAccount;
import com.fsp.core.ledger.GlAccountRepository;
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
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 4 — core order-brain tests (plan §7.1): the price-fingerprint audit bond,
 * idempotent begin/confirm/cancel/sync, the mirror JE intent, and owner/tenant 404s.
 * EmiMoneyClient is mocked at the seam; the EMI side has its own engine tests.
 */
class SkuOrderServiceTest {

    SkuOrderRepository orders;
    GlAccountRepository glAccounts;
    EmiMoneyClient emi;
    JournalService journal;
    SkuOrderService service;

    UUID tenantId = UUID.randomUUID();
    UUID walletId = UUID.randomUUID();
    UUID bookId = UUID.randomUUID();
    UUID chargeId = UUID.randomUUID();
    Actor actor = new Actor(UUID.randomUUID(), "demo-buyer", tenantId,
        Actor.ActorType.PERSON, null);

    @BeforeEach
    void setUp() {
        orders = mock(SkuOrderRepository.class);
        glAccounts = mock(GlAccountRepository.class);
        emi = mock(EmiMoneyClient.class);
        journal = mock(JournalService.class);
        lenient().when(orders.save(any(SkuOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(emi.charge(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(chargeId);
        service = new SkuOrderService(orders, glAccounts, emi, journal, new KeyLocks(),
            new TransactionTemplate(new StubTxManager()));
    }

    private static String fp(UUID walletId, String orderRef) {
        return SkuOrderService.fingerprintOf(walletId, orderRef, 100, 10, 5, "NAD");
    }

    @Test
    void fingerprintGoldenVector() {
        // mirrors EMI ChargeServiceTest.fingerprintGoldenVector — the two strings must
        // stay byte-identical, this shared vector proves the format pins to this digest
        UUID w = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThat(SkuOrderService.fingerprintOf(w, "ORD-1", 100, 10, 5, "NAD"))
            .isEqualTo("7b70f8fbd643eee598a98c3b4899399fd67bb39add74e2663cab7fb59aac7695");
        assertThat(SkuOrderService.fingerprintOf(w, "ORD-1", 1000, 10, 5, "NAD"))
            .isNotEqualTo(SkuOrderService.fingerprintOf(w, "ORD-1", 100, 10, 5, "NAD"));
    }

    @Test
    void beginHappyPathOrdersThenCharges() {
        SkuOrder order = service.begin(tenantId, actor, "ORD-1", "SKU-VTC", "Virtual card top-up",
            bookId, walletId, "NAD", 100, 10, 5);

        assertThat(order.state).isEqualTo(SkuOrder.State.CHARGE_HELD);
        assertThat(order.chargeId).isEqualTo(chargeId);
        assertThat(order.totalMinor.longValueExact()).isEqualTo(115);
        assertThat(order.priceFingerprint).isEqualTo(fp(walletId, "ORD-1")); // computed, never trusted
        verify(emi).charge(eq(walletId), eq(actor.personId()), eq(tenantId), eq("demo-buyer"),
            eq("ORD-1"), eq(BigDecimal.valueOf(100)), eq(BigDecimal.valueOf(10)),
            eq(BigDecimal.valueOf(5)), eq("NAD"), eq(fp(walletId, "ORD-1")));
    }

    @Test
    void beginReplayReturnsOriginalOrderWithoutRerouting() {
        SkuOrder existing = order(SkuOrder.State.CHARGE_HELD);
        existing.priceFingerprint = fp(walletId, "ORD-1");
        when(orders.findByBuyerPersonIdAndOrderRef(actor.personId(), "ORD-1"))
            .thenReturn(Optional.of(existing));
        SkuOrder order = service.begin(tenantId, actor, "ORD-1", "SKU-VTC", "d",
            bookId, walletId, "NAD", 100, 10, 5);
        assertThat(order.id).isEqualTo(existing.id);
        verify(emi, never()).charge(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void replayWithChangedPriceIsAScreamingConflict() {
        SkuOrder existing = order(SkuOrder.State.CHARGE_HELD);
        existing.priceFingerprint = fp(walletId, "ORD-1"); // quoted at 100/10/5
        when(orders.findByBuyerPersonIdAndOrderRef(actor.personId(), "ORD-1"))
            .thenReturn(Optional.of(existing));
        // same orderRef, DIFFERENT quote (repo price changed between calls) → visible, never silent
        assertThatThrownBy(() -> service.begin(tenantId, actor, "ORD-1", "SKU-VTC", "d",
                bookId, walletId, "NAD", 200, 10, 5))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SKU_PRICE_FINGERPRINT_MISMATCH"))
            .extracting("status.value").isEqualTo(409);
        verify(emi, never()).charge(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void foreignSubtreeOrderIs404Not403() {
        SkuOrder foreign = order(SkuOrder.State.CHARGE_HELD);
        foreign.tenantId = UUID.randomUUID(); // different subtree
        foreign.buyerPersonId = actor.personId();
        when(orders.findById(foreign.id)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.confirm(foreign.id, actor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getCode()).isEqualTo("SKU_ORDER_NOT_FOUND");
                assertThat(e.getStatus().value()).isEqualTo(404); // existence never leaks
            });
    }

    @Test
    void confirmCommitsAndCancelReleases() {
        SkuOrder held = order(SkuOrder.State.CHARGE_HELD);
        when(orders.findById(held.id)).thenReturn(Optional.of(held));
        assertThat(service.confirm(held.id, actor).state).isEqualTo(SkuOrder.State.CHARGE_CONFIRMED);
        verify(emi).confirm(chargeId, actor.personId(), tenantId, "demo-buyer");

        SkuOrder stillHeld = order(SkuOrder.State.CHARGE_HELD); // cancel must target a HOLD (mock returns the same object otherwise)
        when(orders.findById(stillHeld.id)).thenReturn(Optional.of(stillHeld));
        assertThat(service.cancel(stillHeld.id, actor).state).isEqualTo(SkuOrder.State.CHARGE_VOIDED);
        verify(emi).voidCharge(chargeId, actor.personId(), tenantId, "demo-buyer");
    }

    @Test
    void syncFoldsEmiTruthIntoTheOrder() {
        SkuOrder held = order(SkuOrder.State.CHARGE_HELD);
        when(orders.findById(held.id)).thenReturn(Optional.of(held));
        when(emi.getCharge(chargeId, actor.personId(), tenantId, "demo-buyer"))
            .thenReturn(json("EXPIRED"));
        assertThat(service.sync(held.id, actor).state).isEqualTo(SkuOrder.State.CHARGE_EXPIRED);
    }

    @Test
    void chargeIntentPostsMirrorJeWithFingerprintBoundDescription() {
        SkuOrder confirmed = order(SkuOrder.State.CHARGE_CONFIRMED);
        confirmed.bookId = bookId;
        when(orders.findById(confirmed.id)).thenReturn(Optional.of(confirmed));
        GlAccount clearing = new GlAccount();
        clearing.id = UUID.randomUUID();
        GlAccount revenue = new GlAccount();
        revenue.id = UUID.randomUUID();
        when(glAccounts.findByBookIdAndCode(bookId, SkuOrderService.GL_CODE_SKU_CLEARING))
            .thenReturn(Optional.of(clearing));
        when(glAccounts.findByBookIdAndCode(bookId, SkuOrderService.GL_CODE_SKU_REVENUE))
            .thenReturn(Optional.of(revenue));
        when(journal.postIntent(eq(tenantId), any(), eq(actor))).thenReturn(new JournalDtos.EntryView(
            UUID.randomUUID(), tenantId, bookId, "NAD", "INVENTORY", LocalDate.now(), LocalDate.now(),
            "d", "ORD-1", "115", "115", "POSTED", null, List.of(),
            actor.personId(), null, Instant.now(), 0, List.of(), null));

        SkuOrder result = service.chargeIntent(confirmed.id, actor);
        assertThat(result.journalEntryId).isNotNull();

        ArgumentCaptor<JournalDtos.CreateEntryRequest> req = ArgumentCaptor.forClass(JournalDtos.CreateEntryRequest.class);
        verify(journal).postIntent(eq(tenantId), req.capture(), eq(actor));
        assertThat(req.getValue().bookId()).isEqualTo(bookId);
        assertThat(req.getValue().lines()).hasSize(2);
        assertThat(req.getValue().lines().get(0).direction()).isEqualTo("DEBIT");
        assertThat(req.getValue().lines().get(0).amount()).isEqualTo("115"); // §2b string
        assertThat(req.getValue().lines().get(0).glAccountId()).isEqualTo(clearing.id);
        assertThat(req.getValue().lines().get(1).direction()).isEqualTo("CREDIT");
        assertThat(req.getValue().lines().get(1).glAccountId()).isEqualTo(revenue.id);
        assertThat(req.getValue().description()).contains(confirmed.orderRef).contains(confirmed.priceFingerprint);

        // idempotent replay: second call posts nothing new
        service.chargeIntent(confirmed.id, actor);
        verify(journal, org.mockito.Mockito.times(1)).postIntent(any(), any(), any());
    }

    @Test
    void chargeIntentFailsLoudlyWhenCoaMissing() {
        SkuOrder confirmed = order(SkuOrder.State.CHARGE_CONFIRMED);
        confirmed.bookId = bookId;
        when(orders.findById(confirmed.id)).thenReturn(Optional.of(confirmed));
        when(glAccounts.findByBookIdAndCode(eq(bookId), org.mockito.ArgumentMatchers.anyInt())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.chargeIntent(confirmed.id, actor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SKU_COA_MISSING"))
            .extracting("status.value").isEqualTo(409);
    }

    @Test
    void beginRejectsBadQuotes() {
        assertThatThrownBy(() -> service.begin(tenantId, actor, "ORD-1", "S", "d", bookId, walletId, "NAD", 0, 0, 0))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SKU_QUOTE_INVALID"))
            .extracting("status.value").isEqualTo(400);
        assertThatThrownBy(() -> service.begin(tenantId, actor, "ORD-1", "S", "d", bookId, walletId, "ZWL", 100, 0, 0))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SKU_CURRENCY_UNSUPPORTED"));
        assertThatThrownBy(() -> service.begin(tenantId, actor, "ORD-1", "S", "d", bookId, walletId, "NAD", 100, -1, 0))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SKU_QUOTE_INVALID"));
    }

    @Test
    void cancelAfterConfirmIsRejectedDisputeFlowIsTheRemedy() {
        SkuOrder confirmed = order(SkuOrder.State.CHARGE_CONFIRMED);
        when(orders.findById(confirmed.id)).thenReturn(Optional.of(confirmed));
        assertThatThrownBy(() -> service.cancel(confirmed.id, actor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SKU_ORDER_COMMITTED"))
            .extracting("status.value").isEqualTo(409);
    }

    // ------------------------------------------------------------------ helpers

    private com.fasterxml.jackson.databind.JsonNode json(String state) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree("{\"id\":\"" + chargeId + "\",\"state\":\"" + state + "\"}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private SkuOrder order(String state) {
        SkuOrder o = new SkuOrder();
        o.id = UUID.randomUUID();
        o.tenantId = tenantId;
        o.buyerPersonId = actor.personId();
        o.orderRef = "ORD-1";
        o.skuCode = "SKU-VTC";
        o.walletId = walletId;
        o.currency = "NAD";
        o.merchMinor = BigDecimal.valueOf(100);
        o.feeMinor = BigDecimal.valueOf(10);
        o.taxMinor = BigDecimal.valueOf(5);
        o.totalMinor = BigDecimal.valueOf(115);
        o.priceFingerprint = fp(walletId, "ORD-1");
        o.state = state;
        o.chargeId = chargeId;
        o.createdAt = Instant.now();
        o.updatedAt = o.createdAt;
        return o;
    }

    static class StubTxManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) { /* no-op */ }

        @Override
        public void rollback(TransactionStatus status) { /* no-op */ }
    }
}
