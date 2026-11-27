package com.fsp.plugin.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Encrypted voucher pool (digital plane). PENDING_CLEARANCE → (four-eyes VOUCHER_RELEASE) ACTIVE → EXHAUSTED. */
@Entity
@Table(name = "voucher_batches")
public class VoucherBatch {

    public static final String STATUS_PENDING_CLEARANCE = "PENDING_CLEARANCE";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_EXHAUSTED = "EXHAUSTED";
    public static final List<String> VALID_STATUSES = List.of(STATUS_PENDING_CLEARANCE, STATUS_ACTIVE, STATUS_EXHAUSTED);

    @Id
    public UUID id;
    @Column(name = "owner_tenant_id", nullable = false)
    public UUID ownerTenantId;
    @Column(name = "digital_product_id", nullable = false)
    public UUID digitalProductId;
    @Column(name = "product_code", nullable = false)
    public String productCode;
    @Column(nullable = false, length = 3)
    public String currency;
    @Column(name = "unit_face_minor", nullable = false)
    public long unitFaceMinor;
    @Column(name = "voucher_count", nullable = false)
    public int voucherCount;
    @Column(nullable = false)
    public String reference;
    @Column(nullable = false)
    public String status = STATUS_PENDING_CLEARANCE;
    @Column(name = "approval_id")
    public UUID approvalId;
    @Column(name = "uploaded_by", nullable = false)
    public UUID uploadedBy;
    @Column(name = "authorized_by")
    public UUID authorizedBy;
    @Version
    @Column(nullable = false)
    public long version;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
    @Column(name = "authorized_at")
    public Instant authorizedAt;
}
