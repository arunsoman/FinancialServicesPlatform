package com.fsp.plugin.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One voucher PIN — stored ONLY encrypted (plans D6); plaintext exists at upload and at release only. */
@Entity
@Table(name = "vouchers")
public class Voucher {

    public static final String STATUS_AVAILABLE = "AVAILABLE";
    /** Claimed by an in-flight dispense; the money call is made OUTSIDE any lock/tx (AGENTS §8).
     *  A reservation older than the grace period with no committed sale returns to AVAILABLE. */
    public static final String STATUS_RESERVED = "RESERVED";
    public static final String STATUS_RELEASED = "RELEASED";
    public static final List<String> VALID_STATUSES = List.of(STATUS_AVAILABLE, STATUS_RESERVED, STATUS_RELEASED);

    @Id
    public UUID id;
    @Column(name = "owner_tenant_id", nullable = false)
    public UUID ownerTenantId;
    @Column(name = "batch_id", nullable = false)
    public UUID batchId;
    @Column(name = "digital_product_id", nullable = false)
    public UUID digitalProductId;
    @Column(name = "encrypted_pin", nullable = false)
    public byte[] encryptedPin;
    @Column(name = "pin_nonce", nullable = false)
    public byte[] pinNonce;
    @Column(name = "pin_tag", nullable = false)
    public byte[] pinTag;
    @Column(name = "masked_pin", nullable = false)
    public String maskedPin;
    @Column(name = "face_value_minor", nullable = false)
    public long faceValueMinor;
    @Column(nullable = false, length = 3)
    public String currency;
    @Column(nullable = false)
    public String status = STATUS_AVAILABLE;
    @Column(name = "reserved_at")
    public Instant reservedAt;
    @Column(name = "released_at")
    public Instant releasedAt;
    @Column(name = "release_sale_id")
    public UUID releaseSaleId;
}
