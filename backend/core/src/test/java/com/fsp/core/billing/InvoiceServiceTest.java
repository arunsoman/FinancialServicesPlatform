package com.fsp.core.billing;

import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.fsp.core.billing.InvoiceWorld.BUYER;
import static com.fsp.core.billing.InvoiceWorld.BUYER_ADMIN;
import static com.fsp.core.billing.InvoiceWorld.OUTSIDER;
import static com.fsp.core.billing.InvoiceWorld.ROOT;
import static com.fsp.core.billing.InvoiceWorld.SELLER;
import static com.fsp.core.billing.InvoiceWorld.SELLER_ADMIN;
import static com.fsp.core.billing.InvoiceWorld.SELLER_CHECKER;
import static com.fsp.core.billing.InvoiceWorld.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Invoicing domain rules: credit terms, exact totals, the state machine, sides and four-eyes voids. */
class InvoiceServiceTest {

    private InvoiceWorld w;

    @BeforeEach
    void setUp() {
        w = new InvoiceWorld();
    }

    private static ApiException refused(Runnable call) {
        ApiException e = catchThrowableOfType(call::run, ApiException.class);
        assertThat(e).as("expected a refusal").isNotNull();
        return e;
    }

    // ---------------------------------------------------------------- create

    @Test
    void aNewInvoiceIsADraftOnThirtyDayCreditWithServerComputedTotals() {
        InvoiceDtos.InvoiceView v = w.service.create(SELLER.id, SELLER_ADMIN, new InvoiceDtos.NewInvoice(BUYER.id, "nad",
            TODAY, null, " PO-1 ", null, List.of(
                new InvoiceDtos.NewLine("Bottled water 24-pack", 10, "12000", 1500),
                new InvoiceDtos.NewLine("Delivery", 1, "333", 1500),  // 49.95 VAT → rounds half-up to 50
                new InvoiceDtos.NewLine("Fresh produce", 2, "500", 0))));

        assertThat(v.status()).isEqualTo("DRAFT");
        assertThat(v.creditDays()).isEqualTo(30);
        assertThat(v.number()).isNull();
        assertThat(v.issueDate()).isNull();
        assertThat(v.plannedIssueDate()).isEqualTo(TODAY);
        assertThat(v.currency()).isEqualTo("NAD");
        assertThat(v.reference()).isEqualTo("PO-1");
        assertThat(v.subtotalMinor()).isEqualTo("121333");        // 120 000 + 333 + 1 000
        assertThat(v.taxMinor()).isEqualTo("18050");              // 18 000 + 50 + 0
        assertThat(v.totalMinor()).isEqualTo("139383");
        assertThat(v.balanceMinor()).isEqualTo("139383");
        assertThat(v.lines()).extracting(InvoiceDtos.InvoiceLineView::taxMinor).containsExactly("18000", "50", "0");
        assertThat(v.seller().name()).isEqualTo("Namib Distributors");
        assertThat(v.buyer().name()).isEqualTo("Swakop Wholesale");
        verify(w.permissions).require(SELLER_ADMIN, "invoice.create", SELLER.id);
    }

    @Test
    void onlyOrganisationsInTheNetworkCanBeInvoiced() {
        ApiException e = refused(() -> w.service.create(SELLER.id, SELLER_ADMIN, new InvoiceDtos.NewInvoice(OUTSIDER.id, "NAD",
            TODAY, 30, null, null, List.of(new InvoiceDtos.NewLine("Rice", 1, "100", 1500)))));
        assertThat(e.getStatus().value()).isEqualTo(422);
        assertThat(e.getCode()).isEqualTo("INVOICE_BUYER");
        assertThat(w.invoiceRows).isEmpty();
        // the counterparties list says the same thing: parent is the platform (not a TENANT) → only the child
        assertThat(w.service.counterparties(SELLER.id, SELLER_ADMIN)).extracting(InvoiceDtos.Counterparty::tenantId)
            .containsExactly(BUYER.id);
    }

    @Test
    void badLinesAndTermsAreRefusedFieldByField() {
        ApiException e = refused(() -> w.service.create(SELLER.id, SELLER_ADMIN, new InvoiceDtos.NewInvoice(BUYER.id, "USD",
            TODAY, 400, null, null, List.of(
                new InvoiceDtos.NewLine("x", 1, "100", 1500),
                new InvoiceDtos.NewLine("Rice", 0, "100", 1500),
                new InvoiceDtos.NewLine("Rice", 1, "1.50", 1500),
                new InvoiceDtos.NewLine("Rice", 1, "100", 1400)))));
        assertThat(e.getCode()).isEqualTo("INVOICE_INVALID");
        assertThat(e.getMessage()).contains("currency", "creditDays", "lines[0].description", "lines[1].quantity",
            "lines[2].unitPriceMinor", "lines[3].taxRateBps");
        assertThat(refused(() -> w.service.create(SELLER.id, SELLER_ADMIN, new InvoiceDtos.NewInvoice(BUYER.id, "NAD",
            TODAY, 30, null, null, List.of()))).getMessage()).contains("1-100 lines");
    }

    @Test
    void machinesNeverDraftInvoices() {
        Actor machine = new Actor(null, "plugin", SELLER.id, Actor.ActorType.MACHINE, "kyc");
        assertThat(refused(() -> w.service.create(SELLER.id, machine, null)).getCode()).isEqualTo("MACHINE_ACTOR");
    }

    @Test
    void aMissingPermissionStopsTheCallBeforeAnythingIsRead() {
        doThrow(ApiException.forbidden("PERMISSION_DENIED", "no")).when(w.permissions).require(BUYER_ADMIN, "invoice.view", BUYER.id);
        assertThat(refused(() -> w.service.list(BUYER.id, BUYER_ADMIN, "SALES", null, null, 0, 20, null)).getCode())
            .isEqualTo("PERMISSION_DENIED");
        verify(w.invoices, never()).findAll(Mockito.<org.springframework.data.jpa.domain.Specification<Invoice>>any(),
            any(org.springframework.data.domain.Pageable.class));
    }

    // ---------------------------------------------------------------- issue

    @Test
    void issuingNumbersTheInvoiceAndFixesTheDueDateFromTheCreditTerms() {
        InvoiceDtos.InvoiceView d = w.draft();
        InvoiceDtos.InvoiceView v = w.service.issue(SELLER.id, SELLER_ADMIN, d.invoiceId(), new InvoiceDtos.IssueRequest(d.version()));
        assertThat(v.status()).isEqualTo("ISSUED");
        assertThat(v.number()).isEqualTo("INV-2026-0007");
        assertThat(v.issueDate()).isEqualTo(TODAY);
        assertThat(v.dueDate()).isEqualTo(LocalDate.of(2026, 12, 24));
        assertThat(v.version()).isEqualTo(d.version() + 1);
        verify(w.outbox).record(eq("Invoice"), eq(d.invoiceId()), eq("Invoice.Issued"), Mockito.contains("INV-2026-0007"));

        // a second press with the old version, or issuing twice, is a conflict — never a second number
        assertThat(refused(() -> w.service.issue(SELLER.id, SELLER_ADMIN, d.invoiceId(), new InvoiceDtos.IssueRequest(d.version())))
            .getCode()).isEqualTo("STALE_VERSION");
        assertThat(refused(() -> w.service.issue(SELLER.id, SELLER_ADMIN, d.invoiceId(), new InvoiceDtos.IssueRequest(v.version())))
            .getCode()).isEqualTo("INVOICE_STATE");
        assertThat(w.nextNumber).isEqualTo(8);
    }

    @Test
    void onlyTheSellerIssuesAndAnyoneElseSeesNothing() {
        InvoiceDtos.InvoiceView d = w.draft();
        ApiException e = refused(() -> w.service.issue(BUYER.id, BUYER_ADMIN, d.invoiceId(), new InvoiceDtos.IssueRequest(d.version())));
        assertThat(e.getStatus().value()).isEqualTo(404);
        assertThat(e.getCode()).isEqualTo("INVOICE_NOT_FOUND");
    }

    // ---------------------------------------------------------------- sides (tenant isolation)

    @Test
    void draftsAreTheSellersAloneAndIssuedInvoicesAreSeenByBothSidesAndTheirAncestors() {
        InvoiceDtos.InvoiceView d = w.draft();
        assertThat(w.service.get(SELLER.id, SELLER_ADMIN, d.invoiceId()).status()).isEqualTo("DRAFT");
        assertThat(refused(() -> w.service.get(BUYER.id, BUYER_ADMIN, d.invoiceId())).getStatus().value()).isEqualTo(404);
        assertThat(refused(() -> w.service.get(ROOT.id, SELLER_ADMIN, d.invoiceId())).getStatus().value()).isEqualTo(404);

        w.service.issue(SELLER.id, SELLER_ADMIN, d.invoiceId(), new InvoiceDtos.IssueRequest(d.version()));
        assertThat(w.service.get(BUYER.id, BUYER_ADMIN, d.invoiceId()).number()).isEqualTo("INV-2026-0007");
        assertThat(w.service.get(ROOT.id, SELLER_ADMIN, d.invoiceId()).number()).isEqualTo("INV-2026-0007");
        assertThat(refused(() -> w.service.get(OUTSIDER.id, SELLER_ADMIN, d.invoiceId())).getStatus().value()).isEqualTo(404);
    }

    @Test
    void filtersAndSortAreValidatedAgainstASafelist() {
        assertThat(InvoiceService.direction(null)).isEqualTo("SALES");
        assertThat(refused(() -> InvoiceService.direction("EVERYTHING")).getCode()).isEqualTo("DIRECTION_INVALID");
        assertThat(InvoiceService.statusFilter("OVERDUE")).isEqualTo(new InvoiceService.StatusFilter(Invoice.Status.OPEN, true));
        assertThat(InvoiceService.statusFilter("void").statuses()).containsExactly("VOID", "VOID_PENDING");
        assertThat(refused(() -> InvoiceService.statusFilter("LOST")).getCode()).isEqualTo("STATUS_INVALID");
        assertThat(InvoiceService.parseSort("dueDate,asc").getOrderFor("dueDate")).isNotNull();
        assertThat(refused(() -> InvoiceService.parseSort("sellerTenantId,asc")).getCode()).isEqualTo("SORT_INVALID");
        assertThat(InvoiceService.likePattern("50%_off")).isEqualTo("%50\\%\\_off%");
    }

    // ---------------------------------------------------------------- confirm a transfer

    @Test
    void theSellerConfirmsATransferAndTheBalanceAndStatusFollow() {
        InvoiceDtos.InvoiceView v = w.issued(); // 115 000 total
        InvoicePayment part = transfer(v.invoiceId(), 15_000);
        InvoiceDtos.InvoiceView after = w.service.confirm(SELLER.id, SELLER_ADMIN, v.invoiceId(), part.id);
        assertThat(after.status()).isEqualTo("PARTIALLY_PAID");
        assertThat(after.paidMinor()).isEqualTo("15000");
        assertThat(after.balanceMinor()).isEqualTo("100000");
        assertThat(after.payments()).singleElement().extracting(InvoiceDtos.PaymentView::status).isEqualTo("SUCCEEDED");

        assertThat(refused(() -> w.service.confirm(SELLER.id, SELLER_ADMIN, v.invoiceId(), part.id)).getCode())
            .isEqualTo("PAYMENT_STATE");

        InvoicePayment rest = transfer(v.invoiceId(), 100_000);
        assertThat(w.service.confirm(SELLER.id, SELLER_ADMIN, v.invoiceId(), rest.id).status()).isEqualTo("PAID");
        // the buyer cannot confirm its own transfer
        assertThat(refused(() -> w.service.confirm(BUYER.id, BUYER_ADMIN, v.invoiceId(), rest.id)).getStatus().value()).isEqualTo(404);
    }

    private InvoicePayment transfer(UUID invoiceId, long amount) {
        InvoicePayment p = new InvoicePayment();
        p.id = UUID.randomUUID();
        p.invoiceId = invoiceId;
        p.method = InvoicePayment.Method.BANK_TRANSFER;
        p.amountMinor = amount;
        p.status = InvoicePayment.Status.AWAITING_CONFIRMATION;
        p.payerPersonId = BUYER_ADMIN.personId();
        w.paymentRows.put(p.id, p);
        return p;
    }

    // ---------------------------------------------------------------- void

    @Test
    void aDraftIsVoidedAtOnce() {
        InvoiceDtos.InvoiceView d = w.draft();
        InvoiceDtos.InvoiceView v = w.service.requestVoid(SELLER.id, SELLER_ADMIN, d.invoiceId(), new InvoiceDtos.VoidRequest(null, d.version()));
        assertThat(v.status()).isEqualTo("VOID");
        verify(w.approvals, never()).open(any(), any(), any(), any(Actor.class), Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void anIssuedInvoiceWaitsForASecondPersonWithAReason() {
        InvoiceDtos.InvoiceView v = w.issued();
        assertThat(refused(() -> w.service.requestVoid(SELLER.id, SELLER_ADMIN, v.invoiceId(), new InvoiceDtos.VoidRequest(" ", v.version())))
            .getMessage()).contains("Say why");

        InvoiceDtos.InvoiceView pending = w.service.requestVoid(SELLER.id, SELLER_ADMIN, v.invoiceId(),
            new InvoiceDtos.VoidRequest("Wrong buyer on the invoice", v.version()));
        assertThat(pending.status()).isEqualTo("VOID_PENDING");
        assertThat(pending.voidApprovalId()).isNotNull();
        verify(w.approvals).open(eq(SELLER.id), eq(ApprovalRequest.OpType.INVOICE_VOID), eq(v.invoiceId()), eq(SELLER_ADMIN),
            Mockito.contains("INV-2026-0007"), Mockito.anyString());

        ApprovalRequest approval = new ApprovalRequest();
        approval.id = pending.voidApprovalId();
        approval.targetResourceId = v.invoiceId();
        approval.makerId = SELLER_ADMIN.personId();
        w.service.voidApproved(approval, SELLER_CHECKER);
        assertThat(w.invoiceRows.get(v.invoiceId()).status).isEqualTo("VOID");
        // the decision is single-shot: a second resolution finds nothing waiting
        assertThat(refused(() -> w.service.voidApproved(approval, SELLER_CHECKER)).getCode()).isEqualTo("INVOICE_STATE");
    }

    @Test
    void aRejectedVoidReopensTheInvoice() {
        InvoiceDtos.InvoiceView v = w.issued();
        InvoiceDtos.InvoiceView pending = w.service.requestVoid(SELLER.id, SELLER_ADMIN, v.invoiceId(),
            new InvoiceDtos.VoidRequest("Duplicate", v.version()));
        ApprovalRequest approval = new ApprovalRequest();
        approval.id = pending.voidApprovalId();
        approval.targetResourceId = v.invoiceId();
        w.service.voidRejected(approval, SELLER_CHECKER);
        Invoice row = w.invoiceRows.get(v.invoiceId());
        assertThat(row.status).isEqualTo("ISSUED");
        assertThat(row.voidApprovalId).isNull();
    }

    @Test
    void anInvoiceWithMoneyOnItCannotBeVoided() {
        InvoiceDtos.InvoiceView v = w.issued();
        transfer(v.invoiceId(), 1_000); // awaiting confirmation is money on its way
        InvoiceDtos.InvoiceView fresh = w.service.get(SELLER.id, SELLER_ADMIN, v.invoiceId());
        assertThat(refused(() -> w.service.requestVoid(SELLER.id, SELLER_ADMIN, v.invoiceId(),
            new InvoiceDtos.VoidRequest("Changed my mind", fresh.version()))).getCode()).isEqualTo("INVOICE_STATE");
    }
}
