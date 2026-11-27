package com.fsp.core.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Stage 4 SKU order header (plan §7.1). Core owns order truth; the EMI charge carries
 * the actual money legs (TB book 806); the price fingerprint is the audit bond between
 * quote and charge. {@code @Version} optimistic locking per AGENTS.md §2.8 — every
 * state transition goes through a KeyLocks-guarded re-read, so lost updates are
 * structurally impossible.
 */
@Entity
@Table(name = "sku_order")
public class SkuOrder {

    /** Order lifecycle — mirrors the EMI charge states at every money boundary. */
    public static final class State {
        public static final String CREATED = "CREATED";             // row persisted pre-EMIhop
        public static final String CHARGE_HELD = "CHARGE_HELD";     // linked pending chain placed
        public static final String CHARGE_CONFIRMED = "CHARGE_CONFIRMED"; // fulfilment done
        public static final String CHARGE_VOIDED = "CHARGE_VOIDED"; // pre-fulfilment cancel
        public static final String CHARGE_EXPIRED = "CHARGE_EXPIRED"; // TB grace lapse
        public static final String CHARGE_FAILED = "CHARGE_FAILED"; // definitive rejection
    }

    @Id
    public UUID id;
    public UUID tenantId;
    public UUID buyerPersonId;
    public String orderRef;
    public String skuCode;
    public String description;
    public UUID bookId;
    public UUID walletId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, columnDefinition = "CHAR(3)")
    public String currency;
    public BigDecimal merchMinor;
    public BigDecimal feeMinor;
    public BigDecimal taxMinor;
    public BigDecimal totalMinor;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "price_fingerprint", nullable = false, columnDefinition = "CHAR(64)")
    public String priceFingerprint;
    public String state;
    public UUID chargeId;
    public UUID journalEntryId;
    public String failureCode;
    public Instant createdAt;
    public Instant updatedAt;

    @Version
    public long version;

    public long total() {
        return totalMinor.longValueExact();
    }
}
