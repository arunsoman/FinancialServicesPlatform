package com.fsp.plugin.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Float funding onto 2050 Merchant Digital Float — four-eyes (PLUGIN:FLOAT_FUNDING + JE). */
@Entity
@Table(name = "float_deposits")
public class FloatDeposit {

    public static final String STATUS_PENDING = "PENDING_APPROVAL";
    public static final String STATUS_COMMITTED = "COMMITTED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final List<String> VALID_STATUSES = List.of(STATUS_PENDING, STATUS_COMMITTED, STATUS_REJECTED);

    @Id
    public UUID id;
    @Column(name = "owner_tenant_id", nullable = false)
    public UUID ownerTenantId;
    @Column(name = "float_code", nullable = false)
    public String floatCode;
    @Column(nullable = false, length = 3)
    public String currency;
    @Column(name = "amount_minor", nullable = false)
    public long amountMinor;
    @Column
    public String note;
    @Column(nullable = false)
    public String status = STATUS_PENDING;
    @Column(name = "journal_entry_id")
    public UUID journalEntryId;
    @Column(name = "approval_id")
    public UUID approvalId;
    @Column(name = "requested_by", nullable = false)
    public UUID requestedBy;
    @Column(name = "approved_by")
    public UUID approvedBy;
    @Version
    @Column(nullable = false)
    public long version;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
    @Column(name = "decided_at")
    public Instant decidedAt;
}
