package com.fsp.emi.charge;

import com.fsp.emi.common.ApiException;
import com.fsp.emi.common.KeyLocks;
import com.fsp.emi.common.Uuid5;
import com.fsp.emi.config.EmiProperties;
import com.fsp.emi.ledger.EmiTbResult;
import com.fsp.emi.ledger.EmiTbService;
import com.fsp.emi.wallet.Wallet;
import com.fsp.emi.wallet.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 4 — SKU money engine tests (plan §7.1). The charge under test: a LINKED pending
 * chain (DR wallet → CR sku/fee/tax clearing) that is atomic across legs, committed or
 * released by a resolving post/void-pending chain, and bound to the core price
 * fingerprint. TB is mocked at the EmiTbService seam (test convention).
 */
class ChargeServiceTest {

    ChargeRepository charges;
    WalletRepository wallets;
    EmiTbService tb;
    ChargeService service;

    UUID actor = UUID.randomUUID();
    Wallet wallet;

    @BeforeEach
    void setUp() {
        charges = mock(ChargeRepository.class);
        wallets = mock(WalletRepository.class);
        tb = mock(EmiTbService.class);

        wallet = new Wallet();
        wallet.id = UUID.randomUUID();
        wallet.personId = actor;
        wallet.currency = "NAD";
        wallet.status = Wallet.Status.ACTIVE;

        lenient().when(tb.bookId()).thenReturn(806);
        lenient().when(charges.save(any(Charge.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(charges.findByWalletIdAndOrderRef(any(), any())).thenReturn(Optional.empty());
        lenient().when(wallets.findById(wallet.id)).thenReturn(Optional.of(wallet));
        lenient().when(tb.createAccounts(anyList())).thenReturn(new EmiTbResult.Ok());
        lenient().when(tb.createTransfers(anyList())).thenReturn(new EmiTbResult.Ok());

        service = new ChargeService(wallets, charges, tb,
            new EmiProperties(), new KeyLocks(), new TransactionTemplate(new StubTxManager()));
    }

    private static String fp(UUID walletId, String orderRef, long merch, long fee, long tax) {
        return ChargeService.fingerprint(walletId, orderRef, merch, fee, tax, "NAD");
    }

    private List<EmiTbService.TransferSpec> capturedLegs() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmiTbService.TransferSpec>> cap = ArgumentCaptor.forClass(List.class);
        verify(tb).createTransfers(cap.capture());
        return cap.getValue();
    }

    @Test
    void fingerprintGoldenVector() {
        // both EMI and core hash "sku|wallet|ref|merch|fee|tax|ccy" — this vector pins the
        // byte-identity; change ONE side and this test fails loudly (R1 audit bond)
        UUID w = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThat(ChargeService.fingerprint(w, "ORD-1", 100, 10, 5, "NAD"))
            .isEqualTo("7b70f8fbd643eee598a98c3b4899399fd67bb39add74e2663cab7fb59aac7695");
        assertThat(ChargeService.fingerprint(w, "ORD-1", 100, 10, 5, "NAD"))
            .isEqualTo(ChargeService.fingerprint(w, "ORD-1", 100, 10, 5, "NAD"));
        assertThat(ChargeService.fingerprint(w, "ORD-1", 120, 10, 5, "NAD"))
            .isNotEqualTo(ChargeService.fingerprint(w, "ORD-1", 100, 10, 5, "NAD"));
    }

    @Test
    void chargePlacesThreeLegLinkedPendingChain() {
        Charge c = service.charge(wallet.id, actor, "ORD-1", 100, 10, 5, "NAD", fp(wallet.id, "ORD-1", 100, 10, 5));

        assertThat(c.state).isEqualTo(Charge.State.HOLD);
        List<EmiTbService.TransferSpec> specs = capturedLegs();
        assertThat(specs).hasSize(3);

        EmiTbService.TransferSpec merch = specs.get(0);
        assertThat(merch.id()).isEqualTo(Uuid5.chargeLegTransferId(c.id, 0));
        assertThat(merch.debitAccountId()).isEqualTo(Uuid5.walletAccountId(wallet.id));
        assertThat(merch.creditAccountId()).isEqualTo(Uuid5.skuClearingAccountId("NAD"));
        assertThat(merch.amount()).isEqualTo(100);
        assertThat(merch.flags() & EmiTbService.FLAG_PENDING).isEqualTo(EmiTbService.FLAG_PENDING);
        assertThat(merch.flags() & EmiTbService.FLAG_LINKED).isEqualTo(EmiTbService.FLAG_LINKED);
        assertThat(merch.timeoutSeconds()).isEqualTo(3024000); // saga grace default
        assertThat(merch.ledger()).isEqualTo(806);

        EmiTbService.TransferSpec fee = specs.get(1);
        assertThat(fee.creditAccountId()).isEqualTo(Uuid5.feeClearingAccountId("NAD"));
        assertThat(fee.amount()).isEqualTo(10);
        assertThat(fee.flags() & EmiTbService.FLAG_LINKED).isEqualTo(EmiTbService.FLAG_LINKED);

        EmiTbService.TransferSpec tax = specs.get(2);
        assertThat(tax.creditAccountId()).isEqualTo(Uuid5.taxLiabilityAccountId("NAD"));
        assertThat(tax.amount()).isEqualTo(5);
        // last leg of the linked chain carries NO linked flag — the chain closes here
        assertThat(tax.flags() & EmiTbService.FLAG_LINKED).isZero();
        assertThat(tax.timeoutSeconds()).isEqualTo(3024000);

        // derived accounts ensured before the hop (sku/fee/tax, history on, no bounds)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmiTbService.AccountSpec>> cap = ArgumentCaptor.forClass(List.class);
        verify(tb).createAccounts(cap.capture());
        assertThat(cap.getValue()).hasSize(3);
        assertThat(cap.getValue()).extracting(EmiTbService.AccountSpec::code)
            .containsExactly(ChargeService.ACCOUNT_CODE_SKU_CLEARING,
                ChargeService.ACCOUNT_CODE_FEE_CLEARING, ChargeService.ACCOUNT_CODE_TAX_LIABILITY);
        assertThat(cap.getValue()).extracting(EmiTbService.AccountSpec::id)
            .containsExactly(Uuid5.skuClearingAccountId("NAD"), Uuid5.feeClearingAccountId("NAD"),
                Uuid5.taxLiabilityAccountId("NAD"));
    }

    @Test
    void singleLegChargeCarriesNoLinkedFlag() {
        service.charge(wallet.id, actor, "ORD-SOLO", 500, 0, 0, "NAD", fp(wallet.id, "ORD-SOLO", 500, 0, 0));
        List<EmiTbService.TransferSpec> specs = capturedLegs();
        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).flags() & EmiTbService.FLAG_LINKED).isZero();
        assertThat(specs.get(0).amount()).isEqualTo(500);
        assertThat(specs.get(0).creditAccountId()).isEqualTo(Uuid5.skuClearingAccountId("NAD"));
    }

    @Test
    void replayedOrderRefReturnsOriginalWithoutRerouting() {
        Charge existing = new Charge();
        existing.id = UUID.randomUUID();
        existing.walletId = wallet.id;
        existing.personId = actor;
        existing.orderRef = "ORD-1";
        existing.priceFingerprint = fp(wallet.id, "ORD-1", 100, 10, 5);
        existing.state = Charge.State.HOLD;
        when(charges.findByWalletIdAndOrderRef(wallet.id, "ORD-1")).thenReturn(Optional.of(existing));

        Charge c = service.charge(wallet.id, actor, "ORD-1", 100, 10, 5, "NAD", existing.priceFingerprint);
        assertThat(c.id).isEqualTo(existing.id);
        verify(tb, never()).createTransfers(anyList());
    }

    @Test
    void fingerprintMismatchIsRejectedBeforeAnyMoneyCall() {
        String wrong = "0".repeat(64);
        assertThatThrownBy(() -> service.charge(wallet.id, actor, "ORD-1", 100, 10, 5, "NAD", wrong))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("EMI_PRICE_FINGERPRINT_MISMATCH"))
            .extracting("status.value").isEqualTo(409);
        verify(tb, never()).createTransfers(anyList());
        verify(tb, never()).createAccounts(anyList());
    }

    @Test
    void nonNadAndZeroTotalAreRejected() {
        assertThatThrownBy(() -> service.charge(wallet.id, actor, "ORD-ZWL", 100, 0, 0, "ZWL", "f"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("EMI_CURRENCY_UNSUPPORTED"))
            .extracting("status.value").isEqualTo(422);
        assertThatThrownBy(() -> service.charge(wallet.id, actor, "ORD-ZERO", 0, 0, 0, "NAD", "f"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("EMI_CHARGE_ZERO"))
            .extracting("status.value").isEqualTo(400);
        assertThatThrownBy(() -> service.charge(wallet.id, actor, "ORD-NEG", -10, 0, 5, "NAD", "f"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("EMI_CHARGE_NEGATIVE"));
        assertThatThrownBy(() -> service.charge(wallet.id, actor, "ORD BAD SPACES", 100, 0, 0, "NAD", "f"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("EMI_ORDER_REF_INVALID"));
    }

    @Test
    void definitiveHoldRejectionMarksChargeVoided() {
        // wallet bound exceeded on the pending debit — EMI decided sufficiency, said no
        when(tb.createTransfers(anyList())).thenReturn(new EmiTbResult.DefinitiveRejection(
            "createTransfers rejected: [index=0 status=exceeds_credits]"));
        assertThatThrownBy(() -> service.charge(wallet.id, actor, "ORD-1", 100_000_000_00L, 10, 5, "NAD",
                fp(wallet.id, "ORD-1", 100_000_000_00L, 10, 5)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("EMI_TB_REJECTED"))
            .extracting("status.value").isEqualTo(409);
        ArgumentCaptor<Charge> saved = ArgumentCaptor.forClass(Charge.class);
        verify(charges, times(2)).save(saved.capture()); // CREATED then VOIDED (row-first doctrine)
        assertThat(saved.getAllValues().get(1).state).isEqualTo(Charge.State.VOIDED);
    }

    @Test
    void unknownTransportStaysHoldAndReplayCarriesSameIds() {
        when(tb.createTransfers(anyList())).thenReturn(new EmiTbResult.UnknownTransport("timeout"));
        assertThatThrownBy(() -> service.charge(wallet.id, actor, "ORD-1", 100, 10, 5, "NAD", fp(wallet.id, "ORD-1", 100, 10, 5)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("EMI_TB_UNKNOWN"))
            .extracting("status.value").isEqualTo(503);

        ArgumentCaptor<Charge> saved = ArgumentCaptor.forClass(Charge.class);
        verify(charges, times(2)).save(saved.capture()); // CREATED then HOLD-unknown retry
        assertThat(saved.getAllValues().get(1).state).isEqualTo(Charge.State.HOLD);

        // the DB row exists with a charge id; the retry replays through idempotency and
        // MUST NOT re-route money — the deterministic leg ids stay pinned to that charge
        UUID chargeId = saved.getAllValues().get(0).id;
        Charge persisted = saved.getAllValues().get(1);
        persisted.state = Charge.State.HOLD;
        when(charges.findByWalletIdAndOrderRef(wallet.id, "ORD-1")).thenReturn(Optional.of(persisted));
        Charge retried = service.charge(wallet.id, actor, "ORD-1", 100, 10, 5, "NAD", persisted.priceFingerprint);
        assertThat(retried.id).isEqualTo(chargeId);
        verify(tb, times(1)).createTransfers(anyList()); // still exactly one hop total
    }

    @Test
    void confirmBuildsResolvingPostPendingChain() {
        Charge held = heldCharge("ORD-1", 100, 10, 5);
        Charge c = service.confirmCharge(held.id, actor);
        assertThat(c.state).isEqualTo(Charge.State.CONFIRMED);

        List<EmiTbService.TransferSpec> specs = capturedLegs();
        assertThat(specs).hasSize(3);
        for (int leg = 0; leg < 3; leg++) {
            EmiTbService.TransferSpec s = specs.get(leg);
            assertThat(s.id()).isEqualTo(Uuid5.chargeCommitTransferId(held.id, leg));
            assertThat(s.pendingId()).isEqualTo(Uuid5.chargeLegTransferId(held.id, leg));
            assertThat(s.flags() & EmiTbService.FLAG_POST_PENDING).isEqualTo(EmiTbService.FLAG_POST_PENDING);
            assertThat(s.amount()).isEqualTo(leg == 0 ? 100 : leg == 1 ? 10 : 5);
            // byte-identical repetition: same accounts as the hold legs
            assertThat(s.debitAccountId()).isEqualTo(Uuid5.walletAccountId(wallet.id));
            assertThat(s.timeoutSeconds()).isZero(); // no timeout on resolution legs
        }
        assertThat(specs.get(0).flags() & EmiTbService.FLAG_LINKED).isEqualTo(EmiTbService.FLAG_LINKED);
        assertThat(specs.get(2).flags() & EmiTbService.FLAG_LINKED).isZero();
    }

    @Test
    void confirmReplayIsIdempotent() {
        Charge held = heldCharge("ORD-1", 100, 10, 5);
        held.state = Charge.State.CONFIRMED;
        Charge c = service.confirmCharge(held.id, actor);
        assertThat(c.state).isEqualTo(Charge.State.CONFIRMED);
        verify(tb, never()).createTransfers(anyList());
    }

    @Test
    void voidBuildsResolvingVoidPendingChain() {
        Charge held = heldCharge("ORD-CANCEL", 100, 10, 5);
        Charge c = service.voidCharge(held.id, actor);
        assertThat(c.state).isEqualTo(Charge.State.VOIDED);
        List<EmiTbService.TransferSpec> specs = capturedLegs();
        assertThat(specs).hasSize(3);
        for (int leg = 0; leg < 3; leg++) {
            assertThat(specs.get(leg).id()).isEqualTo(Uuid5.chargeVoidTransferId(held.id, leg));
            assertThat(specs.get(leg).pendingId()).isEqualTo(Uuid5.chargeLegTransferId(held.id, leg));
            assertThat(specs.get(leg).flags() & EmiTbService.FLAG_VOID_PENDING).isEqualTo(EmiTbService.FLAG_VOID_PENDING);
        }
    }

    @Test
    void foreignOwnerGets404() {
        Charge held = heldCharge("ORD-1", 100, 10, 5);
        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> service.confirmCharge(held.id, stranger))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getCode()).isEqualTo("EMI_NOT_FOUND");
                assertThat(e.getStatus().value()).isEqualTo(404); // existence never leaks
            });
        assertThatThrownBy(() -> service.voidCharge(held.id, stranger))
            .extracting("status.value").isEqualTo(404);
        assertThatThrownBy(() -> service.charge(held.walletId, stranger, "ORD-FOREIGN",
                100, 0, 0, "NAD", fp(held.walletId, "ORD-FOREIGN", 100, 0, 0)))
            .extracting("status.value").isEqualTo(404);
    }

    @Test
    void confirmOnVoidedAndVoidOnConfirmedAreStateConflicts() {
        Charge voided = heldCharge("ORD-1", 100, 10, 5);
        voided.state = Charge.State.VOIDED;
        assertThatThrownBy(() -> service.confirmCharge(voided.id, actor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("EMI_CHARGE_STATE"))
            .extracting("status.value").isEqualTo(409);

        Charge confirmed = heldCharge("ORD-2", 100, 10, 5);
        confirmed.state = Charge.State.CONFIRMED;
        assertThatThrownBy(() -> service.voidCharge(confirmed.id, actor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("EMI_CHARGE_COMMITTED"))
            .extracting("status.value").isEqualTo(409);
    }

    @Test
    void sweepVoidsStaleHoldsOnly() {
        Charge stale = heldCharge("ORD-STALE", 100, 10, 5);
        stale.createdAt = Instant.now().minusSeconds(3024000 + 1);
        Charge fresh = heldCharge("ORD-FRESH", 100, 10, 5);
        when(charges.search(eq(null), eq(Charge.State.HOLD), eq(null), any()))
            .thenReturn(new PageImpl<>(List.of(stale, fresh)));

        int handled = service.sweepExpired();
        assertThat(handled).isEqualTo(1); // only the stale hold; the fresh one is untouched

        List<EmiTbService.TransferSpec> specs = capturedLegs();
        assertThat(specs.get(0).id()).isEqualTo(Uuid5.chargeVoidTransferId(stale.id, 0)); // VOID chain, not COMMIT
        assertThat(stale.state).isEqualTo(Charge.State.VOIDED);
        assertThat(fresh.state).isEqualTo(Charge.State.HOLD);
    }

    @Test
    void sweepRepairsToExpiredWhenTbAlreadyExpiredThePending() {
        Charge stale = heldCharge("ORD-EXPIRED", 100, 10, 5);
        stale.createdAt = Instant.now().minusSeconds(3024000 + 1);
        when(charges.search(eq(null), eq(Charge.State.HOLD), eq(null), any()))
            .thenReturn(new PageImpl<>(List.of(stale)));
        when(tb.createTransfers(anyList())).thenReturn(new EmiTbResult.DefinitiveRejection(
            "createTransfers rejected: [index=0 status=PendingTransferExpired]"));

        int handled = service.sweepExpired();
        assertThat(handled).isEqualTo(1);
        assertThat(stale.state).isEqualTo(Charge.State.EXPIRED); // "timeout is not failure" doctrine
    }

    // ------------------------------------------------------------------ helpers

    private Charge heldCharge(String orderRef, long merch, long fee, long tax) {
        Charge c = new Charge();
        c.id = UUID.randomUUID();
        c.walletId = wallet.id;
        c.personId = actor;
        c.orderRef = orderRef;
        c.currency = "NAD";
        c.merchMinor = java.math.BigDecimal.valueOf(merch);
        c.feeMinor = java.math.BigDecimal.valueOf(fee);
        c.taxMinor = java.math.BigDecimal.valueOf(tax);
        c.totalMinor = java.math.BigDecimal.valueOf(merch + fee + tax);
        c.priceFingerprint = fp(wallet.id, orderRef, merch, fee, tax);
        c.state = Charge.State.HOLD;
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        when(charges.findById(c.id)).thenReturn(Optional.of(c));
        return c;
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
