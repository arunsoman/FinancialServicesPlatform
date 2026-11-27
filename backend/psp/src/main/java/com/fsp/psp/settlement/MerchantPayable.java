package com.fsp.psp.settlement;

import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Merchant payable accrual per (merchant, currency, period) — V1 table. */
@Entity
@Table(name = "merchant_payable")
public class MerchantPayable {

    @Id
    public UUID id;

    @Column(name = "merchant_id", nullable = false)
    public UUID merchantId;

    @Column(nullable = false, columnDefinition = "CHAR(3)")
    @JdbcTypeCode(SqlTypes.CHAR)
    public String currency;

    @Column(name = "period_start", nullable = false)
    public LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    public LocalDate periodEnd;

    /** Net owed to the merchant for this period (closing_debit accruals). */
    @Column(name = "accrual_minor", nullable = false, precision = 20, scale = 0)
    public BigDecimal accrualMinor;

    /** Net already paid out to the merchant (payout sweeps). */
    @Column(name = "paid_minor", nullable = false, precision = 20, scale = 0)
    public BigDecimal paidMinor;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Version
    @Column(nullable = false)
    public long version;
}
