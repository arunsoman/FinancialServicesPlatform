package com.fsp.core.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Wire shapes of the invoicing API — field for field the billing.invoices widget's model.ts
 * (the contract approved in stage 1). Money is a minor-unit STRING; never a double.
 */
public final class InvoiceDtos {

    private InvoiceDtos() {}

    public record PartyRef(UUID tenantId, String name) {}

    public record InvoiceSummary(UUID invoiceId, String number, PartyRef seller, PartyRef buyer, String currency,
                                 LocalDate issueDate, LocalDate dueDate, int creditDays, String totalMinor,
                                 String paidMinor, String balanceMinor, String status, long version) {}

    public record InvoiceLineView(int lineNo, String description, int quantity, String unitPriceMinor,
                                  int taxRateBps, String netMinor, String taxMinor, String totalMinor) {}

    public record PaymentView(UUID paymentId, String method, String amountMinor, String status, String reference,
                              Instant createdAt, Instant confirmedAt, String redirectUrl) {}

    public record InvoiceView(UUID invoiceId, String number, PartyRef seller, PartyRef buyer, String currency,
                              LocalDate issueDate, LocalDate dueDate, int creditDays, String totalMinor,
                              String paidMinor, String balanceMinor, String status, long version,
                              LocalDate plannedIssueDate, String reference, String notes, String subtotalMinor,
                              String taxMinor, List<InvoiceLineView> lines, List<PaymentView> payments,
                              UUID voidApprovalId, Instant createdAt) {}

    public record InvoicePage(List<InvoiceSummary> items, int page, int size, long totalElements,
                              int totalPages, boolean hasNext) {}

    public record InvoiceTotals(String direction, String currency, String outstandingMinor, String overdueMinor,
                                String dueSoonMinor, long openCount, long overdueCount) {}

    public record Counterparty(UUID tenantId, String name, String subType, String currency) {}

    public record Payee(String name, String bankName, String accountEnding) {}

    public record PaymentOption(String method, boolean available, String reason, String walletBalanceMinor,
                                Payee payee, String reference) {}

    // ---------- requests ----------

    public record NewLine(String description, Integer quantity, String unitPriceMinor, Integer taxRateBps) {}

    public record NewInvoice(UUID buyerTenantId, String currency, LocalDate issueDate, Integer creditDays,
                             String reference, String notes, List<NewLine> lines) {}

    public record IssueRequest(Long expectedVersion) {}

    public record PayRequest(String method, String amountMinor, Long expectedVersion) {}

    public record VoidRequest(String reason, Long expectedVersion) {}
}
