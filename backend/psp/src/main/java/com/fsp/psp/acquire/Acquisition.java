package com.fsp.psp.acquire;

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

/**
 * One merchant-acquiring saga (V2 table {@code acquisition}). PSP is pass-through:
 * in-flight funds park in the connector's nominal clearing account, which MUST be zero
 * at each settlement cycle. State machine (plan §11 Stage 5):
 *
 * <pre>
 * CREATED --rail auth accepted--&gt; AUTHORIZED --TB capture pending chain--&gt; CAPTURED
 *   --settlement sweep (commit + closing_debit)--&gt; SETTLED
 * CREATED/CAPTURED --void--&gt; VOIDED    CREATED --rail rejection--&gt; REJECTED
 * </pre>
 */
@Entity
@Table(name = "acquisition")
public class Acquisition {

    public static final String STATE_CREATED = "CREATED";
    public static final String STATE_AUTHORIZED = "AUTHORIZED";
    public static final String STATE_CAPTURED = "CAPTURED";
    public static final String STATE_SETTLED = "SETTLED";
    public static final String STATE_VOIDED = "VOIDED";
    public static final String STATE_REJECTED = "REJECTED";

    @Id
    public UUID id;

    @Column(name = "merchant_id", nullable = false)
    public UUID merchantId;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "connector_code", nullable = false)
    public String connectorCode;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, columnDefinition = "CHAR(3)")
    public String currency;

    /** Gross amount in minor units (merchant's sale). Wire value is a string (platform §2b). */
    @Column(name = "gross_minor", nullable = false, precision = 20, scale = 0)
    public BigDecimal grossMinor;

    /** PSP acquiring fee in minor units — extracted from clearing at capture time. */
    @Column(name = "fee_minor", nullable = false, precision = 20, scale = 0)
    public BigDecimal feeMinor;

    /** Net owed to the merchant (gross − fee) — swept from clearing into merchant payable. */
    @Column(name = "net_minor", nullable = false, precision = 20, scale = 0)
    public BigDecimal netMinor;

    /** Merchant's own order reference — UNIQUE per merchant (replay dedupe). */
    @Column(name = "psp_ref", nullable = false)
    public String pspRef;

    /** Scheme authorization reference once the rail accepted. */
    @Column(name = "rail_ref")
    public String railRef;

    @Column(nullable = false)
    public String state = STATE_CREATED;

    /** Deterministic saga idempotency anchor — UNIQUE. */
    @Column(name = "idempotency_key", nullable = false, unique = true)
    public String idempotencyKey;

    /** Capture date; the settlement sweep groups on (connector, currency, cycle_date). */
    @Column(name = "cycle_date")
    public LocalDate cycleDate;

    /** Stable rejection/failure code for terminal states. */
    @Column(name = "failure_code")
    public String failureCode;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Version
    @Column(nullable = false)
    public long version;

    public long gross() { return grossMinor.longValueExact(); }
    public long fee() { return feeMinor.longValueExact(); }
    public long net() { return netMinor.longValueExact(); }
}
