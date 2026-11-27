package com.fsp.plugin.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A dispense receipt: one voucher + the intent JE that moved its money (plans D3/D7). */
@Entity
@Table(name = "voucher_sales")
public class VoucherSale {

    @Id
    public UUID id;
    @Column(name = "owner_tenant_id", nullable = false)
    public UUID ownerTenantId;
    @Column(name = "digital_product_id", nullable = false)
    public UUID digitalProductId;
    @Column(name = "voucher_id", nullable = false)
    public UUID voucherId;
    @Column(name = "sale_reference", nullable = false)
    public String saleReference;
    @Column(nullable = false)
    public String channel = "POS";
    @Column(name = "amount_minor", nullable = false)
    public long amountMinor;
    @Column(nullable = false, length = 3)
    public String currency;
    @Column(name = "journal_entry_id", nullable = false)
    public UUID journalEntryId;
    @Column(name = "dispensed_by", nullable = false)
    public UUID dispensedBy;
    @Column(name = "dispensed_at", nullable = false)
    public Instant dispensedAt = Instant.now();
}
