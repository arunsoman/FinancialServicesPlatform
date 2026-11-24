package com.fsp.core.billing;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.FspOperation;
import com.fsp.core.common.FspOperation.OperationRisk;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Invoicing API (billing.invoices widget). Browser → BFF (session + CSRF) → gateway (JWT + DPoP,
 * routed from this catalog) → here. Mutations ride the IdempotencyFilter (AGENTS §2e); every
 * decision is made by the services (core is the sole PDP).
 */
@RestController
@RequestMapping("/v1/tenants/{tenantId}/invoices")
public class InvoiceController {

    private final InvoiceService invoices;
    private final InvoicePaymentService payments;

    public InvoiceController(InvoiceService invoices, InvoicePaymentService payments) {
        this.invoices = invoices;
        this.payments = payments;
    }

    @FspOperation(id = "billing.invoices.list", permission = "invoice.view", component = "billing",
        risk = OperationRisk.READ, title = "List an organization's sales or purchase invoices")
    @GetMapping
    public InvoiceDtos.InvoicePage list(@PathVariable UUID tenantId, HttpServletRequest request,
                                        @RequestParam(required = false) String direction,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String sort) {
        return invoices.list(tenantId, actor(request), direction, status, q, page, size, sort);
    }

    @FspOperation(id = "billing.invoices.summary", permission = "invoice.view", component = "billing",
        risk = OperationRisk.READ, title = "Totals of an organization's open invoices")
    @GetMapping("/summary")
    public InvoiceDtos.InvoiceTotals summary(@PathVariable UUID tenantId, HttpServletRequest request,
                                             @RequestParam(required = false) String direction) {
        return invoices.totals(tenantId, actor(request), direction);
    }

    @FspOperation(id = "billing.invoices.counterparties", permission = "invoice.create", component = "billing",
        risk = OperationRisk.READ, title = "Organizations this one may invoice")
    @GetMapping("/counterparties")
    public List<InvoiceDtos.Counterparty> counterparties(@PathVariable UUID tenantId, HttpServletRequest request) {
        return invoices.counterparties(tenantId, actor(request));
    }

    @FspOperation(id = "billing.invoices.get", permission = "invoice.view", component = "billing",
        risk = OperationRisk.READ, title = "View an invoice")
    @GetMapping("/{invoiceId}")
    public InvoiceDtos.InvoiceView get(@PathVariable UUID tenantId, @PathVariable UUID invoiceId,
                                       HttpServletRequest request) {
        return invoices.get(tenantId, actor(request), invoiceId);
    }

    @FspOperation(id = "billing.invoices.options", permission = "invoice.pay", component = "billing",
        risk = OperationRisk.READ, title = "Ways the buyer can pay an invoice")
    @GetMapping("/{invoiceId}/payment-options")
    public List<InvoiceDtos.PaymentOption> options(@PathVariable UUID tenantId, @PathVariable UUID invoiceId,
                                                   HttpServletRequest request) {
        return payments.options(tenantId, actor(request), invoiceId);
    }

    @FspOperation(id = "billing.invoices.create", permission = "invoice.create", component = "billing",
        risk = OperationRisk.WRITE, title = "Draft an invoice")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDtos.InvoiceView create(@PathVariable UUID tenantId, HttpServletRequest request,
                                          @RequestBody InvoiceDtos.NewInvoice body) {
        return invoices.create(tenantId, actor(request), body);
    }

    @FspOperation(id = "billing.invoices.issue", permission = "invoice.create", component = "billing",
        risk = OperationRisk.WRITE, title = "Issue a draft invoice to the buyer")
    @PostMapping("/{invoiceId}/issue")
    public InvoiceDtos.InvoiceView issue(@PathVariable UUID tenantId, @PathVariable UUID invoiceId,
                                         HttpServletRequest request, @RequestBody InvoiceDtos.IssueRequest body) {
        return invoices.issue(tenantId, actor(request), invoiceId, body);
    }

    @FspOperation(id = "billing.invoices.pay", permission = "invoice.pay", component = "billing",
        risk = OperationRisk.WRITE, title = "Pay a received invoice (wallet or bank transfer)")
    @PostMapping("/{invoiceId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDtos.PaymentView pay(@PathVariable UUID tenantId, @PathVariable UUID invoiceId,
                                       HttpServletRequest request, @RequestBody InvoiceDtos.PayRequest body) {
        return payments.pay(tenantId, actor(request), invoiceId, body);
    }

    @FspOperation(id = "billing.invoices.confirm", permission = "invoice.collect", component = "billing",
        risk = OperationRisk.WRITE, title = "Confirm a bank transfer for an invoice arrived")
    @PostMapping("/{invoiceId}/payments/{paymentId}/confirm")
    public InvoiceDtos.InvoiceView confirm(@PathVariable UUID tenantId, @PathVariable UUID invoiceId,
                                           @PathVariable UUID paymentId, HttpServletRequest request) {
        return invoices.confirm(tenantId, actor(request), invoiceId, paymentId);
    }

    /** Draft: discarded at once. Issued: opens a four-eyes INVOICE_VOID approval. */
    @FspOperation(id = "billing.invoices.void", permission = "invoice.void", component = "billing",
        risk = OperationRisk.FOUR_EYES, title = "Void an invoice (issued invoices need a second person)")
    @PostMapping("/{invoiceId}/void")
    public InvoiceDtos.InvoiceView voidInvoice(@PathVariable UUID tenantId, @PathVariable UUID invoiceId,
                                               HttpServletRequest request, @RequestBody InvoiceDtos.VoidRequest body) {
        return invoices.requestVoid(tenantId, actor(request), invoiceId, body);
    }

    private static Actor actor(HttpServletRequest request) {
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (actor == null) throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        return actor;
    }
}
