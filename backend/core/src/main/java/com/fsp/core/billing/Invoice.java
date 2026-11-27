package com.fsp.core.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An invoice one organization (seller) issues to another (buyer) — V41 {@code invoices}.
 *
 * State machine (status preconditions + optimistic lock, AGENTS §2d):
 *   DRAFT → ISSUED (seller issues: number + due date fixed) | VOID (seller discards)
 *   ISSUED ⇄ PARTIALLY_PAID → PAID (payments settle)
 *   ISSUED (no money on it) → VOID_PENDING → VOID (second person approves) | ISSUED (rejected)
 *
 * Money is minor units (BIGINT). Totals are derived from the lines once, at create, by
 * {@link InvoiceMath}; {@code paidMinor} only ever moves when a payment SUCCEEDS.
 */
@Entity
@Table(name = "invoices")
public class Invoice {

    public static final class Status {
        public static final String DRAFT = "DRAFT";
        public static final String ISSUED = "ISSUED";
        public static final String PARTIALLY_PAID = "PARTIALLY_PAID";
        public static final String PAID = "PAID";
        public static final String VOID_PENDING = "VOID_PENDING";
        public static final String VOID = "VOID";

        /** Open for payment. */
        public static final List<String> OPEN = List.of(ISSUED, PARTIALLY_PAID);

        private Status() {}
    }

    @Id
    public UUID id;
    @Column(name = "seller_tenant_id", nullable = false)
    public UUID sellerTenantId;
    @Column(name = "buyer_tenant_id", nullable = false)
    public UUID buyerTenantId;
    public String number;
    @Column(nullable = false)
    public String currency;
    @Column(name = "planned_issue_date", nullable = false)
    public LocalDate plannedIssueDate;
    @Column(name = "issue_date")
    public LocalDate issueDate;
    @Column(name = "due_date")
    public LocalDate dueDate;
    @Column(name = "credit_days", nullable = false)
    public int creditDays;
    @Column(name = "subtotal_minor", nullable = false)
    public long subtotalMinor;
    @Column(name = "tax_minor", nullable = false)
    public long taxMinor;
    @Column(name = "total_minor", nullable = false)
    public long totalMinor;
    @Column(name = "paid_minor", nullable = false)
    public long paidMinor;
    @Column(nullable = false)
    public String status = Status.DRAFT;
    public String reference;
    public String notes;
    @Column(name = "void_reason")
    public String voidReason;
    @Column(name = "void_approval_id")
    public UUID voidApprovalId;
    @Column(name = "created_by", nullable = false)
    public UUID createdBy;

    @Version
    public long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public long balanceMinor() {
        return totalMinor - paidMinor;
    }

    public boolean isOpen() {
        return Status.OPEN.contains(status);
    }
}
