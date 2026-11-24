package com.fsp.core.billing;

import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.approval.ApprovalService;
import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.PermissionService;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * An in-memory world for the invoicing services: repositories backed by maps, a fixed clock,
 * a small org tree  root ─ seller ─ buyer, plus an outsider under root.
 * The optimistic lock is played by the map: saving a stale copy is what JPA would refuse.
 */
final class InvoiceWorld {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    static final Tenant ROOT = tenant("00000000-0000-0000-0000-000000000001", null, "PLATFORM", "FSP Platform");
    static final Tenant SELLER = tenant("00000000-0000-0000-0000-0000000000a1", ROOT, "TENANT", "Namib Distributors");
    static final Tenant BUYER = tenant("00000000-0000-0000-0000-0000000000b1", SELLER, "TENANT", "Swakop Wholesale");
    static final Tenant OUTSIDER = tenant("00000000-0000-0000-0000-0000000000c1", ROOT, "TENANT", "Elsewhere Ltd");

    static final Actor SELLER_ADMIN = new Actor(UUID.randomUUID(), "seller-admin", SELLER.id, Actor.ActorType.PERSON, null);
    static final Actor SELLER_CHECKER = new Actor(UUID.randomUUID(), "seller-checker", SELLER.id, Actor.ActorType.PERSON, null);
    static final Actor BUYER_ADMIN = new Actor(UUID.randomUUID(), "buyer-admin", BUYER.id, Actor.ActorType.PERSON, null);

    final Map<UUID, Invoice> invoiceRows = new LinkedHashMap<>();
    final Map<UUID, InvoicePayment> paymentRows = new LinkedHashMap<>();
    final List<InvoiceLine> lineRows = new ArrayList<>();
    int nextNumber = 7;

    final InvoiceRepository invoices = Mockito.mock(InvoiceRepository.class);
    final InvoiceLineRepository lines = Mockito.mock(InvoiceLineRepository.class);
    final InvoicePaymentRepository payments = Mockito.mock(InvoicePaymentRepository.class);
    final TenantRepository tenants = Mockito.mock(TenantRepository.class);
    final PermissionService permissions = Mockito.mock(PermissionService.class);
    final ApprovalService approvals = Mockito.mock(ApprovalService.class);
    final AuditService audit = Mockito.mock(AuditService.class);
    final OutboxService outbox = Mockito.mock(OutboxService.class);
    final InvoiceService service;

    InvoiceWorld() {
        Map<UUID, Tenant> all = Map.of(ROOT.id, ROOT, SELLER.id, SELLER, BUYER.id, BUYER, OUTSIDER.id, OUTSIDER);
        when(tenants.findById(any())).thenAnswer(i -> Optional.ofNullable(all.get((UUID) i.getArgument(0))));
        when(tenants.findAllById(any())).thenAnswer(i -> {
            List<Tenant> out = new ArrayList<>();
            for (Object id : (Iterable<?>) i.getArgument(0)) if (all.containsKey(id)) out.add(all.get(id));
            return out;
        });
        when(tenants.findByParentTenantId(any(UUID.class), any(org.springframework.data.domain.Pageable.class))).thenAnswer(i ->
            new PageImpl<>(all.values().stream().filter(t -> i.getArgument(0).equals(t.parentTenantId)).toList()));

        when(invoices.findById(any())).thenAnswer(i -> Optional.ofNullable(copy(invoiceRows.get((UUID) i.getArgument(0)))));
        when(invoices.saveAndFlush(any())).thenAnswer(i -> save(i.getArgument(0)));
        when(invoices.nextNumber(any(), Mockito.anyInt())).thenAnswer(i -> nextNumber++);
        when(invoices.inSubtree(any(), any(), any())).thenAnswer(i -> {
            Tenant t = all.get((UUID) i.getArgument(0));
            String path = i.getArgument(1);
            return t != null && (t.hierarchyPath.equals(path) || t.hierarchyPath.startsWith(path + "/"));
        });
        when(lines.saveAll(any())).thenAnswer(i -> {
            for (Object l : (Iterable<?>) i.getArgument(0)) lineRows.add((InvoiceLine) l);
            return i.getArgument(0);
        });
        when(lines.findByInvoiceIdOrderByLineNo(any())).thenAnswer(i ->
            lineRows.stream().filter(l -> l.invoiceId.equals(i.getArgument(0))).toList());
        when(payments.findById(any())).thenAnswer(i -> Optional.ofNullable(paymentRows.get((UUID) i.getArgument(0))));
        when(payments.saveAndFlush(any())).thenAnswer(i -> {
            InvoicePayment p = i.getArgument(0);
            if (p.createdAt == null) p.createdAt = Instant.parse("2026-11-24T09:00:00Z");
            paymentRows.put(p.id, p);
            return p;
        });
        when(payments.findByInvoiceIdOrderByCreatedAtAscIdAsc(any())).thenAnswer(i ->
            paymentRows.values().stream().filter(p -> p.invoiceId.equals(i.getArgument(0))).toList());
        when(payments.sumByStatus(any(), any())).thenAnswer(i -> {
            Collection<?> st = i.getArgument(1);
            return paymentRows.values().stream().filter(p -> p.invoiceId.equals(i.getArgument(0)) && st.contains(p.status))
                .mapToLong(p -> p.amountMinor).sum();
        });
        when(payments.countLive(any())).thenAnswer(i -> paymentRows.values().stream()
            .filter(p -> p.invoiceId.equals(i.getArgument(0)) && !InvoicePayment.Status.FAILED.equals(p.status)).count());
        when(approvals.open(any(), any(), any(), any(Actor.class), Mockito.anyString(), Mockito.anyString())).thenAnswer(i -> {
            ApprovalRequest a = new ApprovalRequest();
            a.id = UUID.randomUUID();
            a.tenantId = i.getArgument(0);
            a.operationType = i.getArgument(1);
            a.targetResourceId = i.getArgument(2);
            a.makerId = ((Actor) i.getArgument(3)).personId();
            return a;
        });
        service = new InvoiceService(invoices, lines, payments, tenants, permissions, approvals, audit, outbox,
            Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    /** Saving plays JPA's @Version: a stale copy is refused, a fresh one bumps the version. */
    private Invoice save(Invoice in) {
        Invoice current = invoiceRows.get(in.id);
        if (current != null && current.version != in.version) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Invoice.class, in.id);
        }
        Invoice stored = copy(in);
        if (stored.createdAt == null) stored.createdAt = Instant.parse("2026-11-24T08:00:00Z");
        if (current != null) stored.version = current.version + 1;
        invoiceRows.put(stored.id, stored);
        in.version = stored.version;
        in.createdAt = stored.createdAt;
        return in;
    }

    static Invoice copy(Invoice i) {
        if (i == null) return null;
        Invoice c = new Invoice();
        c.id = i.id; c.sellerTenantId = i.sellerTenantId; c.buyerTenantId = i.buyerTenantId; c.number = i.number;
        c.currency = i.currency; c.plannedIssueDate = i.plannedIssueDate; c.issueDate = i.issueDate; c.dueDate = i.dueDate;
        c.creditDays = i.creditDays; c.subtotalMinor = i.subtotalMinor; c.taxMinor = i.taxMinor; c.totalMinor = i.totalMinor;
        c.paidMinor = i.paidMinor; c.status = i.status; c.reference = i.reference; c.notes = i.notes; c.voidReason = i.voidReason;
        c.voidApprovalId = i.voidApprovalId; c.createdBy = i.createdBy; c.version = i.version; c.createdAt = i.createdAt;
        c.updatedAt = i.updatedAt;
        return c;
    }

    /** A draft of 1 000.00 + 15 % VAT from SELLER to BUYER. */
    InvoiceDtos.InvoiceView draft() {
        return service.create(SELLER.id, SELLER_ADMIN, new InvoiceDtos.NewInvoice(BUYER.id, "NAD", TODAY, null, null, null,
            List.of(new InvoiceDtos.NewLine("Maize meal 12.5 kg", 10, "10000", 1500))));
    }

    InvoiceDtos.InvoiceView issued() {
        InvoiceDtos.InvoiceView d = draft();
        return service.issue(SELLER.id, SELLER_ADMIN, d.invoiceId(), new InvoiceDtos.IssueRequest(d.version()));
    }

    private static Tenant tenant(String id, Tenant parent, String type, String name) {
        Tenant t = new Tenant();
        t.id = UUID.fromString(id);
        t.parentTenantId = parent == null ? null : parent.id;
        t.hierarchyPath = (parent == null ? "" : parent.hierarchyPath) + "/" + id;
        t.type = type;
        t.status = "ACTIVE";
        t.legalName = name;
        t.subType = "TENANT".equals(type) ? "DISTRIBUTOR" : null;
        return t;
    }
}
