package com.fsp.core.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.core.common.ApiException;
import com.fsp.core.ledger.BankAccount;
import com.fsp.core.ledger.BankAccountRepository;
import com.fsp.core.orders.EmiMoneyClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.fsp.core.billing.InvoiceWorld.BUYER;
import static com.fsp.core.billing.InvoiceWorld.BUYER_ADMIN;
import static com.fsp.core.billing.InvoiceWorld.SELLER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Paying an invoice: the options the server offers, reservations against the balance, and the EMI wallet rail. */
class InvoicePaymentServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID WALLET = UUID.fromString("00000000-0000-0000-0000-00000000aa11");

    private InvoiceWorld w;
    private EmiMoneyClient emi;
    private BankAccountRepository bankAccounts;
    private InvoicePaymentService service;
    private InvoiceDtos.InvoiceView inv; // issued, 115 000 NAD

    @BeforeEach
    void setUp() {
        w = new InvoiceWorld();
        emi = Mockito.mock(EmiMoneyClient.class);
        bankAccounts = Mockito.mock(BankAccountRepository.class);
        service = new InvoicePaymentService(w.service, w.invoices, w.payments, bankAccounts, emi, w.permissions,
            w.audit, w.outbox, Mockito.mock(PlatformTransactionManager.class));
        inv = w.issued();
        BankAccount account = new BankAccount();
        account.holderName = "Namib Distributors (Pty) Ltd";
        account.bankCode = "BANK_WINDHOEK";
        account.accountNumber = "62001234542";
        account.currency = "NAD";
        account.status = BankAccount.Status.ACTIVE;
        when(bankAccounts.findByTenantIdAndStatus(SELLER.id, BankAccount.Status.ACTIVE)).thenReturn(List.of(account));
    }

    private void wallet(long balance) {
        JsonNode node = JSON.createObjectNode().put("id", WALLET.toString()).put("status", "ACTIVE").put("balance", Long.toString(balance));
        when(emi.myWallet(eq(BUYER_ADMIN.personId()), any(), any())).thenReturn(Optional.of(node));
    }

    private long version() {
        return w.invoiceRows.get(inv.invoiceId()).version;
    }

    private InvoiceDtos.PaymentView pay(String method, long amount) {
        return service.pay(BUYER.id, BUYER_ADMIN, inv.invoiceId(), new InvoiceDtos.PayRequest(method, Long.toString(amount), version()));
    }

    private static ApiException refused(Runnable call) {
        ApiException e = catchThrowableOfType(call::run, ApiException.class);
        assertThat(e).as("expected a refusal").isNotNull();
        return e;
    }

    // ---------------------------------------------------------------- options

    @Test
    void optionsShowTheSellersBankByItsLastTwoDigitsAndSayWhyCardIsOff() {
        wallet(50_000);
        List<InvoiceDtos.PaymentOption> options = service.options(BUYER.id, BUYER_ADMIN, inv.invoiceId());
        assertThat(options).extracting(InvoiceDtos.PaymentOption::method).containsExactly("WALLET", "BANK_TRANSFER", "CARD");

        InvoiceDtos.PaymentOption wallet = options.get(0);
        assertThat(wallet.available()).isTrue();
        assertThat(wallet.walletBalanceMinor()).isEqualTo("50000");
        assertThat(wallet.reason()).contains("covers part");

        InvoiceDtos.PaymentOption eft = options.get(1);
        assertThat(eft.payee()).isEqualTo(new InvoiceDtos.Payee("Namib Distributors (Pty) Ltd", "Bank Windhoek", "42"));
        assertThat(eft.reference()).isEqualTo("INV-2026-0007");
        assertThat(eft.toString()).doesNotContain("62001234542");

        assertThat(options.get(2).available()).isFalse();
        assertThat(options.get(2).reason()).contains("not switched on");
    }

    @Test
    void optionsDegradeInWordsWhenTheBuyerHasNoWalletOrTheSellerNoBank() {
        when(emi.myWallet(any(), any(), any())).thenReturn(Optional.empty());
        when(bankAccounts.findByTenantIdAndStatus(SELLER.id, BankAccount.Status.ACTIVE)).thenReturn(List.of());
        List<InvoiceDtos.PaymentOption> options = service.options(BUYER.id, BUYER_ADMIN, inv.invoiceId());
        assertThat(options).allMatch(o -> !o.available() && o.reason() != null);

        when(emi.myWallet(any(), any(), any())).thenThrow(new ResourceAccessException("emi down"));
        assertThat(service.options(BUYER.id, BUYER_ADMIN, inv.invoiceId()).get(0).reason()).contains("not answering");
    }

    @Test
    void onlyTheBuyerSeesPaymentOptions() {
        assertThat(refused(() -> service.options(SELLER.id, InvoiceWorld.SELLER_ADMIN, inv.invoiceId())).getStatus().value())
            .isEqualTo(404);
    }

    // ---------------------------------------------------------------- bank transfer

    @Test
    void aTransferIsRecordedAndReservesItsAmountUntilTheSellerConfirms() {
        InvoiceDtos.PaymentView p = pay("BANK_TRANSFER", 100_000);
        assertThat(p.status()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(p.reference()).isEqualTo("INV-2026-0007");
        assertThat(w.invoiceRows.get(inv.invoiceId()).paidMinor).isZero(); // not money until confirmed

        // 15 000 left that is not already on its way
        ApiException e = refused(() -> pay("BANK_TRANSFER", 15_001));
        assertThat(e.getStatus().value()).isEqualTo(422);
        assertThat(e.getCode()).isEqualTo("PAYMENT_EXCEEDS_BALANCE");
        assertThat(pay("BANK_TRANSFER", 15_000).status()).isEqualTo("AWAITING_CONFIRMATION");
    }

    @Test
    void aPaymentWithAStaleVersionIsAConflict() {
        long seen = version();
        pay("BANK_TRANSFER", 1_000);
        assertThat(refused(() -> service.pay(BUYER.id, BUYER_ADMIN, inv.invoiceId(),
            new InvoiceDtos.PayRequest("BANK_TRANSFER", "1000", seen))).getCode()).isEqualTo("STALE_VERSION");
    }

    @Test
    void cardIsRefusedUntilACardRailExists() {
        ApiException e = refused(() -> pay("CARD", 1_000));
        assertThat(e.getCode()).isEqualTo("PAYMENT_METHOD_UNAVAILABLE");
        assertThat(w.paymentRows).isEmpty();
    }

    @Test
    void theAmountMustBeAPositiveWholeNumberOfMinorUnits() {
        for (String bad : new String[] {"0", "-5", "12.50", "1e3", ""}) {
            assertThat(refused(() -> service.pay(BUYER.id, BUYER_ADMIN, inv.invoiceId(),
                new InvoiceDtos.PayRequest("WALLET", bad, version()))).getCode()).isEqualTo("PAYMENT_INVALID");
        }
    }

    @Test
    void permissionIsCheckedAtTheBuyer() {
        doThrow(ApiException.forbidden("PERMISSION_DENIED", "no")).when(w.permissions).require(BUYER_ADMIN, "invoice.pay", BUYER.id);
        assertThat(refused(() -> pay("BANK_TRANSFER", 1_000)).getCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(w.paymentRows).isEmpty();
    }

    // ---------------------------------------------------------------- wallet (EMI)

    @Test
    void aWalletPaymentChargesAndConfirmsAtTheEmiAndSettlesAtOnce() {
        wallet(500_000);
        UUID charge = UUID.randomUUID();
        when(emi.charge(eq(WALLET), eq(BUYER_ADMIN.personId()), any(), any(), anyString(), any(), any(), any(), eq("NAD"), anyString()))
            .thenReturn(charge);

        InvoiceDtos.PaymentView p = pay("WALLET", 40_000);

        assertThat(p.status()).isEqualTo("SUCCEEDED");
        assertThat(p.reference()).startsWith("EMI-");
        verify(emi).confirm(eq(charge), eq(BUYER_ADMIN.personId()), any(), any());
        Invoice row = w.invoiceRows.get(inv.invoiceId());
        assertThat(row.paidMinor).isEqualTo(40_000);
        assertThat(row.status).isEqualTo("PARTIALLY_PAID");
        // the charge is bound to this payment: orderRef and the fingerprint over the exact amount
        verify(emi).charge(eq(WALLET), any(), any(), any(), eq("inv-pay:" + p.paymentId()),
            eq(java.math.BigDecimal.valueOf(40_000)), eq(java.math.BigDecimal.ZERO), eq(java.math.BigDecimal.ZERO), eq("NAD"),
            eq(com.fsp.core.orders.SkuOrderService.fingerprintOf(WALLET, "inv-pay:" + p.paymentId(), 40_000, 0, 0, "NAD")));
    }

    @Test
    void aWalletThatDoesNotCoverTheAmountIsRefusedBeforeAnythingIsReserved() {
        wallet(10_000);
        assertThat(refused(() -> pay("WALLET", 40_000)).getCode()).isEqualTo("WALLET_INSUFFICIENT");
        assertThat(w.paymentRows).isEmpty();
        verify(emi, never()).charge(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anEmiRefusalFailsThePaymentAndFreesTheReservation() {
        wallet(500_000);
        when(emi.charge(any(), any(), any(), any(), anyString(), any(), any(), any(), any(), anyString()))
            .thenThrow(new ApiException(HttpStatus.CONFLICT, "EMI_TB_REJECTED", "rejected by the ledger"));

        ApiException e = refused(() -> pay("WALLET", 40_000));
        assertThat(e.getCode()).isEqualTo("EMI_TB_REJECTED");
        assertThat(e.getStatus().value()).isEqualTo(422);
        assertThat(w.paymentRows.values()).singleElement().satisfies(p -> {
            assertThat(p.status).isEqualTo("FAILED");
            assertThat(p.failureCode).isEqualTo("EMI_TB_REJECTED");
        });
        assertThat(w.invoiceRows.get(inv.invoiceId()).paidMinor).isZero();
        assertThat(pay("BANK_TRANSFER", 115_000).status()).isEqualTo("AWAITING_CONFIRMATION"); // nothing left reserved
    }

    @Test
    void aRefusedConfirmReleasesTheHold() {
        wallet(500_000);
        UUID charge = UUID.randomUUID();
        when(emi.charge(any(), any(), any(), any(), anyString(), any(), any(), any(), any(), anyString())).thenReturn(charge);
        doThrow(new ApiException(HttpStatus.CONFLICT, "EMI_CHARGE_STATE", "no")).when(emi).confirm(eq(charge), any(), any(), any());
        refused(() -> pay("WALLET", 40_000));
        verify(emi).voidCharge(eq(charge), any(), any(), any());
    }

    @Test
    void whenTheRailDoesNotAnswerTheOutcomeIsUnknownAndTheAmountStaysReserved() {
        wallet(500_000);
        UUID charge = UUID.randomUUID();
        when(emi.charge(any(), any(), any(), any(), anyString(), any(), any(), any(), any(), anyString())).thenReturn(charge);
        doThrow(new ResourceAccessException("read timed out")).when(emi).confirm(eq(charge), any(), any(), any());

        InvoiceDtos.PaymentView p = pay("WALLET", 40_000);

        assertThat(p.status()).isEqualTo("COMMIT_UNKNOWN");
        assertThat(w.paymentRows.get(p.paymentId()).railRef).isEqualTo(charge.toString());
        assertThat(w.invoiceRows.get(inv.invoiceId()).paidMinor).isZero();
        assertThat(refused(() -> pay("BANK_TRANSFER", 75_001)).getCode()).isEqualTo("PAYMENT_EXCEEDS_BALANCE");
        verify(emi, never()).voidCharge(any(), any(), any(), any()); // never release what may have committed
    }

    @Test
    void walletsPayNadOnly() {
        InvoiceDtos.InvoiceView zar = w.service.create(SELLER.id, InvoiceWorld.SELLER_ADMIN, new InvoiceDtos.NewInvoice(BUYER.id, "ZAR",
            InvoiceWorld.TODAY, null, null, null, List.of(new InvoiceDtos.NewLine("Rice", 1, "1000", 0))));
        InvoiceDtos.InvoiceView issued = w.service.issue(SELLER.id, InvoiceWorld.SELLER_ADMIN, zar.invoiceId(),
            new InvoiceDtos.IssueRequest(zar.version()));
        assertThat(refused(() -> service.pay(BUYER.id, BUYER_ADMIN, issued.invoiceId(),
            new InvoiceDtos.PayRequest("WALLET", "100", issued.version()))).getCode()).isEqualTo("PAYMENT_METHOD_UNAVAILABLE");
        assertThat(service.options(BUYER.id, BUYER_ADMIN, issued.invoiceId()).get(0).reason()).contains("NAD invoices only");
    }
}
