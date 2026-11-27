package com.fsp.psp.merchant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A merchant acquiring through the PSP (V1 table {@code merchant}). PSP never owns
 * merchant money — the payable liability is settled within {@code payOverDays}.
 */
@Entity
@Table(name = "merchant")
public class Merchant {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";

    @Id
    public UUID id;

    /** Owning tenant — subtree scoping (platform §4): all reads/writes filter on this. */
    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "merchant_code", nullable = false, unique = true)
    public String merchantCode;

    @Column(name = "display_name", nullable = false)
    public String displayName;

    @Column(nullable = false)
    public String status = STATUS_PENDING;

    /** PSD-1 §12.2.3 pay-over window (business days) — platform default 2. */
    @Column(name = "pay_over_days", nullable = false)
    public int payOverDays = 2;

    /** External merchant bank account reference (for payouts) — never a full account number. */
    @Column(name = "merchant_bank_account_ref")
    public String merchantBankAccountRef;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Version
    @Column(nullable = false)
    public long version;

    /** Wire-safe gross/fee minor accessor — NUMERIC(20,0) in the DB. */
    public BigDecimal payOver() {
        return BigDecimal.valueOf(payOverDays);
    }
}
