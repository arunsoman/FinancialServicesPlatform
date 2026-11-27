package com.fsp.plugin.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A stock receipt batch (physical plane). STAGED → (four-eyes STOCK_RECEIPT + JE) COMMITTED | REJECTED. */
@Entity
@Table(name = "inventory_stock_batches")
public class InventoryStockBatch {

    public static final String STATUS_STAGED = "STAGED";
    public static final String STATUS_COMMITTED = "COMMITTED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final List<String> VALID_STATUSES = List.of(STATUS_STAGED, STATUS_COMMITTED, STATUS_REJECTED);

    @Id
    public UUID id;
    @Column(name = "owner_tenant_id", nullable = false)
    public UUID ownerTenantId;
    @Column(name = "product_id", nullable = false)
    public UUID productId;
    @Column(name = "sku_code", nullable = false)
    public String skuCode;
    @Column(name = "sku_name", nullable = false)
    public String skuName;
    @Column(nullable = false, length = 3)
    public String currency;
    @Column(nullable = false)
    public int quantity;
    @Column(name = "unit_cost_minor", nullable = false)
    public long unitCostMinor;
    @Column(name = "total_cost_minor", nullable = false)
    public long totalCostMinor;
    @Column(nullable = false)
    public String reference;
    @Column
    public String note;
    @Column(nullable = false)
    public String status = STATUS_STAGED;
    @Column(name = "journal_entry_id")
    public UUID journalEntryId;
    @Column(name = "approval_id")
    public UUID approvalId;
    @Column(name = "received_by", nullable = false)
    public UUID receivedBy;
    @Column(name = "approved_by")
    public UUID approvedBy;
    @Version
    @Column(nullable = false)
    public long version;
    @Column(name = "received_at", nullable = false)
    public Instant receivedAt = Instant.now();
    @Column(name = "decided_at")
    public Instant decidedAt;
}
