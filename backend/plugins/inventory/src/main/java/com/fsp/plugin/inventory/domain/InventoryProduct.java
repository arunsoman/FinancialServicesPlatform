package com.fsp.plugin.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** SKU (physical plane). Pricing goes through four-eyes (PLUGIN:SKU_PRICING) before ACTIVE. */
@Entity
@Table(name = "inventory_products")
public class InventoryProduct {

    public static final String STATUS_PENDING = "PENDING_APPROVAL";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final List<String> VALID_STATUSES = List.of(STATUS_PENDING, STATUS_ACTIVE, STATUS_REJECTED);

    @Id
    public UUID id;
    @Column(name = "owner_tenant_id", nullable = false)
    public UUID ownerTenantId;
    @Column(nullable = false)
    public String code;
    @Column(nullable = false)
    public String name;
    @Column(nullable = false)
    public String unit = "EA";
    @Column(name = "unit_cost_minor", nullable = false)
    public long unitCostMinor;
    @Column(name = "wholesale_price_minor", nullable = false)
    public long wholesalePriceMinor;
    @Column(name = "rrp_minor", nullable = false)
    public long rrpMinor;
    @Column(nullable = false, length = 3)
    public String currency;
    @Column(nullable = false)
    public String status = STATUS_PENDING;
    @Column(name = "open_approval_id")
    public UUID openApprovalId;
    @Column(name = "created_by", nullable = false)
    public UUID createdBy;
    @Column(name = "approved_by")
    public UUID approvedBy;
    @Version
    @Column(nullable = false)
    public long version;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();
}
