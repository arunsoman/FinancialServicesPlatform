package com.fsp.core.billing;

import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.approval.ApprovalService;
import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.PermissionService;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Invoicing (billing.invoices). Core is the PDP: every call checks the permission at the
 * organization in the path, then that the invoice belongs to that organization's side —
 * seller, buyer (never a draft), or, for an ancestor such as the platform, a party inside
 * its subtree (AGENTS §2.4). Anything else is a 404: existence never leaks across orgs.
 *
 * Money: totals are computed here from the lines (half-up tax, exact long arithmetic) and the
 * paid amount only moves when a payment SUCCEEDS (see {@link InvoicePaymentService}).
 * Concurrency: every write states the version it saw ({@code expectedVersion}); the row's
 * {@code @Version} makes a lost update impossible (AGENTS §2d). No remote I/O in here.
 */
@Slf4j
@Service
public class InvoiceService {

    public static final String SALES = "SALES";
    public static final String PURCHASES = "PURCHASES";
    public static final String ALL = "ALL";
    static final int DUE_SOON_DAYS = 7;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final String TOTALS_CURRENCY = "NAD";

    /** Sort safelist: wire field → entity property (AGENTS §2f). */
    private static final Map<String, String> SORT_FIELDS = Map.of(
        "createdAt", "createdAt", "dueDate", "dueDate", "issueDate", "issueDate",
        "number", "number", "totalMinor", "totalMinor");

    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final InvoicePaymentRepository payments;
    private final TenantRepository tenants;
    private final PermissionService permissions;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final OutboxService outbox;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public InvoiceService(InvoiceRepository invoices, InvoiceLineRepository lines, InvoicePaymentRepository payments,
                          TenantRepository tenants, PermissionService permissions, ApprovalService approvals,
                          AuditService audit, OutboxService outbox) {
        this(invoices, lines, payments, tenants, permissions, approvals, audit, outbox, Clock.systemUTC());
    }

    InvoiceService(InvoiceRepository invoices, InvoiceLineRepository lines, InvoicePaymentRepository payments,
                   TenantRepository tenants, PermissionService permissions, ApprovalService approvals,
                   AuditService audit, OutboxService outbox, Clock clock) {
        this.invoices = invoices;
        this.lines = lines;
        this.payments = payments;
        this.tenants = tenants;
        this.permissions = permissions;
        this.approvals = approvals;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    // ============================================================ reads

    /** One page of an organization's invoices — filter, search, sort and paging all in SQL. */
    @Transactional(readOnly = true)
    public InvoiceDtos.InvoicePage list(UUID tenantId, Actor actor, String direction, String status, String q,
                                        Integer page, Integer size, String sort) {
        permissions.require(actor, "invoice.view", tenantId);
        Tenant org = org(tenantId);
        String dir = direction(direction);
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Page<Invoice> rows = invoices.findAll(listSpec(org, dir, statusFilter(status), likePattern(q), today()),
            PageRequest.of(p, s, parseSort(sort)));
        Map<UUID, String> names = names(rows.getContent().stream()
            .flatMap(i -> java.util.stream.Stream.of(i.sellerTenantId, i.buyerTenantId)).collect(Collectors.toSet()));
        List<InvoiceDtos.InvoiceSummary> items = rows.getContent().stream().map(i -> summary(i, names)).toList();
        return new InvoiceDtos.InvoicePage(items, p, s, rows.getTotalElements(), Math.max(1, rows.getTotalPages()),
            rows.hasNext());
    }

    /** Totals of the open invoices on one side (NAD — the platform's settlement currency). */
    @Transactional(readOnly = true)
    public InvoiceDtos.InvoiceTotals totals(UUID tenantId, Actor actor, String direction) {
        permissions.require(actor, "invoice.view", tenantId);
        Tenant org = org(tenantId);
        String dir = direction(direction);
        LocalDate today = today();
        InvoiceRepository.Totals t = invoices.totals(org.id, dir, org.hierarchyPath + "/%", TOTALS_CURRENCY,
            today, today.plusDays(DUE_SOON_DAYS));
        return new InvoiceDtos.InvoiceTotals(dir, TOTALS_CURRENCY, Long.toString(t.getOutstanding()),
            Long.toString(t.getOverdue()), Long.toString(t.getDueSoon()), t.getOpenCount(), t.getOverdueCount());
    }

    @Transactional(readOnly = true)
    public InvoiceDtos.InvoiceView get(UUID tenantId, Actor actor, UUID invoiceId) {
        permissions.require(actor, "invoice.view", tenantId);
        return view(visible(org(tenantId), invoiceId));
    }

    /**
     * The organizations this one may invoice: its parent and its direct children, active
     * organizations only (bounded — a form dropdown, at most {@value #MAX_COUNTERPARTIES}).
     */
    @Transactional(readOnly = true)
    public List<InvoiceDtos.Counterparty> counterparties(UUID tenantId, Actor actor) {
        permissions.require(actor, "invoice.create", tenantId);
        return network(org(tenantId)).stream()
            .map(t -> new InvoiceDtos.Counterparty(t.id, t.legalName, t.subType, TOTALS_CURRENCY))
            .toList();
    }

    static final int MAX_COUNTERPARTIES = 200;

    private List<Tenant> network(Tenant org) {
        List<Tenant> out = new ArrayList<>();
        if (org.parentTenantId != null) tenants.findById(org.parentTenantId).filter(InvoiceService::tradable).ifPresent(out::add);
        tenants.findByParentTenantId(org.id, PageRequest.of(0, MAX_COUNTERPARTIES, Sort.by("legalName", "id")))
            .getContent().stream().filter(InvoiceService::tradable).forEach(out::add);
        out.sort(Comparator.comparing((Tenant t) -> Objects.requireNonNullElse(t.legalName, "")).thenComparing(t -> t.id));
        return out;
    }

    private static boolean tradable(Tenant t) {
        return "TENANT".equals(t.type) && "ACTIVE".equals(t.status);
    }

    // ============================================================ create / issue

    @Transactional
    public InvoiceDtos.InvoiceView create(UUID tenantId, Actor maker, InvoiceDtos.NewInvoice in) {
        permissions.require(maker, "invoice.create", tenantId);
        requirePerson(maker);
        Tenant seller = org(tenantId);
        if (in == null) throw ApiException.badRequest("BODY_INVALID", "Request body required");
        List<String> errors = new ArrayList<>();
        String currency = in.currency() == null ? "" : in.currency().trim().toUpperCase(Locale.ROOT);
        if (!InvoiceMath.CURRENCIES.contains(currency)) errors.add("currency must be one of " + InvoiceMath.CURRENCIES);
        int creditDays = in.creditDays() == null ? InvoiceMath.DEFAULT_CREDIT_DAYS : in.creditDays();
        if (creditDays < 0 || creditDays > 365) errors.add("creditDays must be 0-365");
        LocalDate issueDate = in.issueDate() == null ? today() : in.issueDate();
        if (Math.abs(issueDate.toEpochDay() - today().toEpochDay()) > 365) errors.add("issueDate must be within a year of today");
        String reference = trimToNull(in.reference());
        if (reference != null && reference.length() > 64) errors.add("reference must be at most 64 characters");
        String notes = trimToNull(in.notes());
        if (notes != null && notes.length() > 500) errors.add("notes must be at most 500 characters");
        List<InvoiceDtos.NewLine> rawLines = in.lines() == null ? List.of() : in.lines();
        if (rawLines.isEmpty() || rawLines.size() > InvoiceMath.MAX_LINES) {
            errors.add("an invoice needs 1-" + InvoiceMath.MAX_LINES + " lines");
        }

        UUID id = UUID.randomUUID();
        List<InvoiceLine> built = new ArrayList<>();
        long sub = 0;
        long tax = 0;
        try {
            for (int i = 0; i < rawLines.size(); i++) {
                InvoiceDtos.NewLine l = rawLines.get(i);
                String where = "lines[" + i + "]";
                String desc = l == null ? null : trimToNull(l.description());
                if (desc == null || desc.length() < 2 || desc.length() > 200) { errors.add(where + ".description must be 2-200 characters"); continue; }
                if (l.quantity() == null || l.quantity() < 1 || l.quantity() > 999_999) { errors.add(where + ".quantity must be 1-999999"); continue; }
                if (l.unitPriceMinor() == null || !l.unitPriceMinor().matches("[1-9]\\d{0,12}")) {
                    errors.add(where + ".unitPriceMinor must be a positive whole number of minor units"); continue;
                }
                if (l.taxRateBps() == null || !InvoiceMath.TAX_RATES_BPS.contains(l.taxRateBps())) {
                    errors.add(where + ".taxRateBps must be one of " + InvoiceMath.TAX_RATES_BPS); continue;
                }
                long unit = Long.parseLong(l.unitPriceMinor());
                InvoiceMath.Amounts a = InvoiceMath.line(l.quantity(), unit, l.taxRateBps());
                InvoiceLine row = new InvoiceLine();
                row.invoiceId = id;
                row.lineNo = i + 1;
                row.description = desc;
                row.quantity = l.quantity();
                row.unitPriceMinor = unit;
                row.taxRateBps = l.taxRateBps();
                row.netMinor = a.net();
                row.taxMinor = a.tax();
                row.totalMinor = a.total();
                built.add(row);
                sub = Math.addExact(sub, a.net());
                tax = Math.addExact(tax, a.tax());
            }
        } catch (ArithmeticException overflow) {
            errors.add("the invoice total is too large");
        }
        if (!errors.isEmpty()) throw ApiException.badRequest("INVOICE_INVALID", String.join("; ", errors));

        UUID buyerId = in.buyerTenantId();
        if (buyerId == null || network(seller).stream().noneMatch(t -> t.id.equals(buyerId))) {
            log.warn("invoice_buyer_refused seller={} buyer={} by={}", tenantId, buyerId, maker.personId());
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVOICE_BUYER",
                "You can only invoice organisations in your network.");
        }

        Invoice inv = new Invoice();
        inv.id = id;
        inv.sellerTenantId = tenantId;
        inv.buyerTenantId = buyerId;
        inv.currency = currency;
        inv.plannedIssueDate = issueDate;
        inv.creditDays = creditDays;
        inv.subtotalMinor = sub;
        inv.taxMinor = tax;
        inv.totalMinor = sub + tax;
        inv.status = Invoice.Status.DRAFT;
        inv.reference = reference;
        inv.notes = notes;
        inv.createdBy = maker.personId();
        invoices.saveAndFlush(inv);
        lines.saveAll(built);

        audit.record(maker.personId(), null, tenantId, null, "INVOICE_DRAFTED", "Invoice", id.toString(),
            null, audit.hashState(fingerprint(inv)), false);
        log.info("invoice_drafted id={} seller={} buyer={} total={} currency={} by={}",
            id, tenantId, buyerId, inv.totalMinor, currency, maker.personId());
        return view(inv);
    }

    @Transactional
    public InvoiceDtos.InvoiceView issue(UUID tenantId, Actor actor, UUID invoiceId, InvoiceDtos.IssueRequest in) {
        permissions.require(actor, "invoice.create", tenantId);
        requirePerson(actor);
        Invoice inv = asSeller(tenantId, invoiceId);
        expectVersion(inv, in == null ? null : in.expectedVersion());
        if (!Invoice.Status.DRAFT.equals(inv.status)) throw stateConflict(inv, "Only a draft can be issued.");
        String before = fingerprint(inv);
        int year = inv.plannedIssueDate.getYear();
        inv.number = InvoiceMath.number(year, invoices.nextNumber(tenantId, year));
        inv.issueDate = inv.plannedIssueDate;
        inv.dueDate = InvoiceMath.dueDate(inv.issueDate, inv.creditDays);
        inv.status = Invoice.Status.ISSUED;
        invoices.saveAndFlush(inv);
        audit.record(actor.personId(), null, tenantId, null, "INVOICE_ISSUED", "Invoice", inv.id.toString(),
            audit.hashState(before), audit.hashState(fingerprint(inv)), false);
        outbox.record("Invoice", inv.id, "Invoice.Issued",
            "{\"invoice\":\"%s\",\"number\":\"%s\",\"seller\":\"%s\",\"buyer\":\"%s\",\"totalMinor\":\"%d\",\"currency\":\"%s\",\"dueDate\":\"%s\"}"
                .formatted(inv.id, inv.number, inv.sellerTenantId, inv.buyerTenantId, inv.totalMinor, inv.currency, inv.dueDate));
        log.info("invoice_issued id={} number={} due={} by={}", inv.id, inv.number, inv.dueDate, actor.personId());
        return view(inv);
    }

    // ============================================================ seller confirms a transfer

    @Transactional
    public InvoiceDtos.InvoiceView confirm(UUID tenantId, Actor actor, UUID invoiceId, UUID paymentId) {
        permissions.require(actor, "invoice.collect", tenantId);
        requirePerson(actor);
        Invoice inv = asSeller(tenantId, invoiceId);
        InvoicePayment p = payments.findById(paymentId).filter(x -> x.invoiceId.equals(inv.id))
            .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "No such payment on this invoice."));
        if (!InvoicePayment.Method.BANK_TRANSFER.equals(p.method)
            || !InvoicePayment.Status.AWAITING_CONFIRMATION.equals(p.status)) {
            log.warn("invoice_payment_transition_conflict payment={} status={} method={}", p.id, p.status, p.method);
            throw ApiException.conflict("PAYMENT_STATE", "This payment is not waiting for confirmation.");
        }
        p.confirmedBy = actor.personId();
        settled(inv, p);
        audit.record(actor.personId(), null, tenantId, null, "INVOICE_PAYMENT_CONFIRMED", "InvoicePayment",
            p.id.toString(), null, audit.hashState("amount=" + p.amountMinor + ";status=SUCCEEDED"), false);
        return view(inv);
    }

    /**
     * A payment succeeded: it counts toward the invoice and the status follows the balance.
     * Caller holds the transaction; the invoice save is version-checked.
     */
    void settled(Invoice inv, InvoicePayment p) {
        if (!Invoice.Status.OPEN.contains(inv.status)) {
            // money arrived on an invoice that is no longer open — record it, never drop it
            log.error("invoice_payment_on_closed_invoice invoice={} status={} payment={}", inv.id, inv.status, p.id);
        }
        p.status = InvoicePayment.Status.SUCCEEDED;
        p.confirmedAt = java.time.Instant.now();
        payments.saveAndFlush(p);
        inv.paidMinor = Math.addExact(inv.paidMinor, p.amountMinor);
        if (inv.paidMinor > inv.totalMinor) {
            throw new IllegalStateException("invoice %s overpaid: paid %d > total %d".formatted(inv.id, inv.paidMinor, inv.totalMinor));
        }
        if (Invoice.Status.OPEN.contains(inv.status)) {
            inv.status = inv.paidMinor == inv.totalMinor ? Invoice.Status.PAID : Invoice.Status.PARTIALLY_PAID;
        }
        invoices.saveAndFlush(inv);
        outbox.record("Invoice", inv.id, "Invoice.PaymentReceived",
            "{\"invoice\":\"%s\",\"payment\":\"%s\",\"method\":\"%s\",\"amountMinor\":\"%d\",\"currency\":\"%s\",\"status\":\"%s\"}"
                .formatted(inv.id, p.id, p.method, p.amountMinor, inv.currency, inv.status));
        log.info("invoice_payment_succeeded invoice={} payment={} method={} amount={} status={}",
            inv.id, p.id, p.method, p.amountMinor, inv.status);
    }

    // ============================================================ void

    /** A draft is discarded at once; an issued invoice with no money on it waits for a second person. */
    @Transactional
    public InvoiceDtos.InvoiceView requestVoid(UUID tenantId, Actor maker, UUID invoiceId, InvoiceDtos.VoidRequest in) {
        permissions.require(maker, "invoice.void", tenantId);
        requirePerson(maker);
        Invoice inv = asSeller(tenantId, invoiceId);
        expectVersion(inv, in == null ? null : in.expectedVersion());
        String reason = in == null ? null : trimToNull(in.reason());
        if (reason != null && reason.length() > 500) throw ApiException.badRequest("INVOICE_INVALID", "reason must be at most 500 characters");
        String before = fingerprint(inv);

        if (Invoice.Status.DRAFT.equals(inv.status)) {
            inv.status = Invoice.Status.VOID;
            inv.voidReason = reason;
            invoices.saveAndFlush(inv);
            audit.record(maker.personId(), null, tenantId, null, "INVOICE_DRAFT_VOIDED", "Invoice", inv.id.toString(),
                audit.hashState(before), audit.hashState(fingerprint(inv)), false);
            log.info("invoice_draft_voided id={} by={}", inv.id, maker.personId());
            return view(inv);
        }
        if (!Invoice.Status.ISSUED.equals(inv.status) || payments.countLive(inv.id) > 0) {
            throw stateConflict(inv, "An invoice with payments cannot be voided — issue a credit note instead.");
        }
        if (reason == null || reason.length() < 2) {
            throw ApiException.badRequest("INVOICE_INVALID", "Say why, so the approver and the buyer know");
        }
        inv.status = Invoice.Status.VOID_PENDING;
        inv.voidReason = reason;
        invoices.saveAndFlush(inv);
        ApprovalRequest approval = approvals.open(tenantId, ApprovalRequest.OpType.INVOICE_VOID, inv.id, maker,
            "invoice-void:" + inv.number + ":" + inv.totalMinor + ":" + inv.currency,
            "{\"invoice\":\"%s\",\"number\":\"%s\",\"buyer\":\"%s\",\"totalMinor\":\"%d\",\"currency\":\"%s\"}"
                .formatted(inv.id, inv.number, inv.buyerTenantId, inv.totalMinor, inv.currency));
        inv.voidApprovalId = approval.id;
        invoices.saveAndFlush(inv);
        audit.record(maker.personId(), null, tenantId, null, "INVOICE_VOID_REQUESTED", "Invoice", inv.id.toString(),
            audit.hashState(before), audit.hashState(fingerprint(inv)), false);
        log.info("invoice_void_requested id={} number={} approvalId={} by={}", inv.id, inv.number, approval.id, maker.personId());
        return view(inv);
    }

    /** Approval effect (same transaction as the approval row): the checker approved the void. */
    @Transactional
    public void voidApproved(ApprovalRequest approval, Actor checker) {
        Invoice inv = pendingVoid(approval);
        String before = fingerprint(inv);
        inv.status = Invoice.Status.VOID;
        invoices.saveAndFlush(inv);
        audit.record(checker.personId(), approval.makerId, inv.sellerTenantId, null, "INVOICE_VOIDED", "Invoice",
            inv.id.toString(), audit.hashState(before), audit.hashState(fingerprint(inv)), false);
        outbox.record("Invoice", inv.id, "Invoice.Voided",
            "{\"invoice\":\"%s\",\"number\":\"%s\",\"seller\":\"%s\",\"buyer\":\"%s\"}"
                .formatted(inv.id, inv.number, inv.sellerTenantId, inv.buyerTenantId));
        log.info("invoice_voided id={} number={} checker={}", inv.id, inv.number, checker.personId());
    }

    /** Approval effect: the checker rejected the void — the invoice is open again. */
    @Transactional
    public void voidRejected(ApprovalRequest approval, Actor checker) {
        Invoice inv = pendingVoid(approval);
        String before = fingerprint(inv);
        inv.status = Invoice.Status.ISSUED;
        inv.voidApprovalId = null;
        inv.voidReason = null;
        invoices.saveAndFlush(inv);
        audit.record(checker.personId(), approval.makerId, inv.sellerTenantId, null, "INVOICE_VOID_REJECTED", "Invoice",
            inv.id.toString(), audit.hashState(before), audit.hashState(fingerprint(inv)), false);
        log.warn("invoice_void_rejected id={} number={} checker={}", inv.id, inv.number, checker.personId());
    }

    private Invoice pendingVoid(ApprovalRequest approval) {
        Invoice inv = invoices.findById(approval.targetResourceId)
            .orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "Invoice %s not found".formatted(approval.targetResourceId)));
        if (!Invoice.Status.VOID_PENDING.equals(inv.status) || !approval.id.equals(inv.voidApprovalId)) {
            log.warn("invoice_transition_conflict id={} from={} approval={}", inv.id, inv.status, approval.id);
            throw ApiException.conflict("INVOICE_STATE", "Invoice %s is %s — no void is waiting".formatted(inv.id, inv.status));
        }
        return inv;
    }

    // ============================================================ helpers shared with payments

    Tenant org(UUID tenantId) {
        return tenants.findById(tenantId)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Organization not found"));
    }

    /** The invoice as its seller sees it; anyone else gets a 404. */
    Invoice asSeller(UUID tenantId, UUID invoiceId) {
        return invoices.findById(invoiceId).filter(i -> i.sellerTenantId.equals(tenantId))
            .orElseThrow(InvoiceService::notFound);
    }

    /** The invoice as its buyer sees it (never a draft); anyone else gets a 404. */
    Invoice asBuyer(UUID tenantId, UUID invoiceId) {
        return invoices.findById(invoiceId)
            .filter(i -> i.buyerTenantId.equals(tenantId) && !Invoice.Status.DRAFT.equals(i.status))
            .orElseThrow(InvoiceService::notFound);
    }

    /** Seller, buyer (not a draft), or an ancestor of either party (not a draft). */
    Invoice visible(Tenant org, UUID invoiceId) {
        Invoice inv = invoices.findById(invoiceId).orElseThrow(InvoiceService::notFound);
        if (inv.sellerTenantId.equals(org.id)) return inv;
        if (Invoice.Status.DRAFT.equals(inv.status)) throw notFound();
        if (inv.buyerTenantId.equals(org.id)) return inv;
        String prefix = org.hierarchyPath + "/%";
        if (invoices.inSubtree(inv.sellerTenantId, org.hierarchyPath, prefix)
            || invoices.inSubtree(inv.buyerTenantId, org.hierarchyPath, prefix)) return inv;
        throw notFound();
    }

    static ApiException notFound() {
        return ApiException.notFound("INVOICE_NOT_FOUND", "No such invoice here.");
    }

    static void expectVersion(Invoice inv, Long expected) {
        if (expected == null) throw ApiException.badRequest("VERSION_REQUIRED", "expectedVersion is required");
        if (expected != inv.version) {
            log.warn("invoice_stale_version id={} expected={} actual={}", inv.id, expected, inv.version);
            throw ApiException.conflict("STALE_VERSION", "Someone changed this invoice — reload and try again.");
        }
    }

    static ApiException stateConflict(Invoice inv, String message) {
        log.warn("invoice_transition_conflict id={} from={}", inv.id, inv.status);
        return ApiException.conflict("INVOICE_STATE", message);
    }

    static void requirePerson(Actor actor) {
        if (actor.personId() == null || actor.type() == Actor.ActorType.MACHINE) {
            throw ApiException.forbidden("MACHINE_ACTOR", "Invoices are created, paid and voided by people");
        }
    }

    InvoiceDtos.InvoiceView view(Invoice inv) {
        Map<UUID, String> names = names(Set.of(inv.sellerTenantId, inv.buyerTenantId));
        List<InvoiceDtos.InvoiceLineView> ls = lines.findByInvoiceIdOrderByLineNo(inv.id).stream()
            .map(l -> new InvoiceDtos.InvoiceLineView(l.lineNo, l.description, l.quantity, Long.toString(l.unitPriceMinor),
                l.taxRateBps, Long.toString(l.netMinor), Long.toString(l.taxMinor), Long.toString(l.totalMinor)))
            .toList();
        List<InvoiceDtos.PaymentView> ps = payments.findByInvoiceIdOrderByCreatedAtAscIdAsc(inv.id).stream()
            .map(InvoiceService::paymentView).toList();
        return new InvoiceDtos.InvoiceView(inv.id, inv.number, party(inv.sellerTenantId, names), party(inv.buyerTenantId, names),
            inv.currency, inv.issueDate, inv.dueDate, inv.creditDays, Long.toString(inv.totalMinor),
            Long.toString(inv.paidMinor), Long.toString(inv.balanceMinor()), inv.status, inv.version,
            inv.plannedIssueDate, inv.reference, inv.notes, Long.toString(inv.subtotalMinor), Long.toString(inv.taxMinor),
            ls, ps, Invoice.Status.VOID_PENDING.equals(inv.status) ? inv.voidApprovalId : null, inv.createdAt);
    }

    static InvoiceDtos.PaymentView paymentView(InvoicePayment p) {
        return new InvoiceDtos.PaymentView(p.id, p.method, Long.toString(p.amountMinor), p.status, p.reference,
            p.createdAt, p.confirmedAt, null);
    }

    // ============================================================ internals

    private InvoiceDtos.InvoiceSummary summary(Invoice i, Map<UUID, String> names) {
        return new InvoiceDtos.InvoiceSummary(i.id, i.number, party(i.sellerTenantId, names), party(i.buyerTenantId, names),
            i.currency, i.issueDate, i.dueDate, i.creditDays, Long.toString(i.totalMinor), Long.toString(i.paidMinor),
            Long.toString(i.balanceMinor()), i.status, i.version);
    }

    private static InvoiceDtos.PartyRef party(UUID id, Map<UUID, String> names) {
        return new InvoiceDtos.PartyRef(id, names.getOrDefault(id, "Unknown organisation"));
    }

    private Map<UUID, String> names(Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return tenants.findAllById(new HashSet<>(ids)).stream()
            .collect(Collectors.toMap(t -> t.id, t -> Objects.requireNonNullElse(t.legalName, "Unnamed organisation"),
                (a, b) -> a, LinkedHashMap::new));
    }

    private static String fingerprint(Invoice i) {
        return "status=%s;number=%s;total=%d;paid=%d;due=%s".formatted(i.status, i.number, i.totalMinor, i.paidMinor, i.dueDate);
    }

    static String direction(String direction) {
        if (direction == null || direction.isBlank()) return SALES;
        String d = direction.trim().toUpperCase(Locale.ROOT);
        if (!List.of(SALES, PURCHASES, ALL).contains(d)) {
            throw ApiException.badRequest("DIRECTION_INVALID", "direction must be SALES, PURCHASES or ALL");
        }
        return d;
    }

    /** Filter chip → statuses; OVERDUE is derived (open and past due); null = every status. */
    record StatusFilter(List<String> statuses, boolean overdue) {}

    static StatusFilter statusFilter(String status) {
        if (status == null || status.isBlank() || status.trim().equalsIgnoreCase("ALL")) return new StatusFilter(null, false);
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "DRAFT" -> new StatusFilter(List.of(Invoice.Status.DRAFT), false);
            case "OPEN" -> new StatusFilter(Invoice.Status.OPEN, false);
            case "OVERDUE" -> new StatusFilter(Invoice.Status.OPEN, true);
            case "PAID" -> new StatusFilter(List.of(Invoice.Status.PAID), false);
            case "VOID" -> new StatusFilter(List.of(Invoice.Status.VOID, Invoice.Status.VOID_PENDING), false);
            default -> throw ApiException.badRequest("STATUS_INVALID", "status must be ALL, DRAFT, OPEN, OVERDUE, PAID or VOID");
        };
    }

    static Sort parseSort(String sort) {
        String key = sort == null || sort.isBlank() ? "createdAt,desc" : sort.trim();
        String[] parts = key.split(",", -1);
        String field = SORT_FIELDS.get(parts[0].trim());
        String dir = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "asc";
        if (field == null || parts.length > 2 || !(dir.equals("asc") || dir.equals("desc"))) {
            throw ApiException.badRequest("SORT_INVALID", "Sort must be one of "
                + SORT_FIELDS.keySet().stream().sorted().toList() + " followed by ,asc or ,desc");
        }
        Sort.Order primary = new Sort.Order(dir.equals("desc") ? Sort.Direction.DESC : Sort.Direction.ASC, field,
            Sort.NullHandling.NULLS_LAST);
        return Sort.by(primary).and(Sort.by(Sort.Direction.DESC, "createdAt")).and(Sort.by("id"));
    }

    static String likePattern(String q) {
        if (q == null || q.isBlank()) return null;
        String t = q.trim().toLowerCase(Locale.ROOT);
        if (t.length() > 100) t = t.substring(0, 100);
        return "%" + t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    static Specification<Invoice> listSpec(Tenant org, String direction, StatusFilter status, String like, LocalDate today) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            switch (direction) {
                case SALES -> where.add(cb.equal(root.get("sellerTenantId"), org.id));
                case PURCHASES -> {
                    where.add(cb.equal(root.get("buyerTenantId"), org.id));
                    where.add(cb.notEqual(root.get("status"), Invoice.Status.DRAFT));
                }
                default -> { // ALL: either party inside the org's subtree; drafts stay the seller's
                    Subquery<UUID> subtree = query.subquery(UUID.class);
                    Root<Tenant> t = subtree.from(Tenant.class);
                    subtree.select(t.get("id")).where(cb.or(
                        cb.equal(t.get("id"), org.id),
                        cb.like(t.get("hierarchyPath"), org.hierarchyPath + "/%")));
                    where.add(cb.notEqual(root.get("status"), Invoice.Status.DRAFT));
                    where.add(cb.or(root.get("sellerTenantId").in(subtree), root.get("buyerTenantId").in(subtree)));
                }
            }
            if (status.statuses() != null) where.add(root.get("status").in(status.statuses()));
            if (status.overdue()) where.add(cb.lessThan(root.<LocalDate>get("dueDate"), today));
            if (like != null) {
                Subquery<UUID> named = query.subquery(UUID.class);
                Root<Tenant> t = named.from(Tenant.class);
                named.select(t.get("id")).where(cb.like(cb.lower(cb.coalesce(t.<String>get("legalName"), "")), like, '\\'));
                where.add(cb.or(
                    cb.like(cb.lower(cb.coalesce(root.<String>get("number"), "")), like, '\\'),
                    cb.like(cb.lower(cb.coalesce(root.<String>get("reference"), "")), like, '\\'),
                    root.get("sellerTenantId").in(named),
                    root.get("buyerTenantId").in(named)));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
