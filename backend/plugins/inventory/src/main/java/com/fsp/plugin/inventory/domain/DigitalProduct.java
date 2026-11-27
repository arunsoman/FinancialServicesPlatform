package com.fsp.plugin.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Digital value product (digital plane) — voucher kinds first (plans D6/D7). */
@Entity
@Table(name = "digital_products")
public class DigitalProduct {

    public static final String KIND_VOUCHER = "VOUCHER";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final List<String> VALID_STATUSES = List.of(STATUS_ACTIVE, STATUS_SUSPENDED);

    @Id
    public UUID id;
    @Column(name = "owner_tenant_id", nullable = false)
    public UUID ownerTenantId;
    @Column(nullable = false)
    public String code;
    @Column(nullable = false)
    public String name;
    @Column(nullable = false)
    public String kind = KIND_VOUCHER;
    @Column(nullable = false)
    public String status = STATUS_ACTIVE;
    @Column(name = "sale_price_minor", nullable = false)
    public long salePriceMinor;
    @Column(name = "cost_price_minor", nullable = false)
    public long costPriceMinor;
    @Column(nullable = false, length = 3)
    public String currency;
    @Column(name = "telco_bp", nullable = false)
    public int telcoBp = 9400;
    @Column(name = "distributor_bp", nullable = false)
    public int distributorBp = 200;
    @Column(name = "platform_bp", nullable = false)
    public int platformBp = 100;
    @Column(name = "margin_gl_code", nullable = false)
    public int marginGlCode = 4000;
    @Version
    @Column(nullable = false)
    public long version;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
}
