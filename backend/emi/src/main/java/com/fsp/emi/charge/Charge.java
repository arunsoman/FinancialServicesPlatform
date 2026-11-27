package com.fsp.emi.charge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One wallet-funded SKU order charge (Stage 4, plan §7.1): a LINKED PENDING chain of TB
 * transfers held against the wallet until the fulfilment precondition flips (core's
 * confirm). The row is the idempotency anchor — UNIQUE (wallet_id, order_ref) so a replayed
 * order can never place a second hold — and the audit home of the price fingerprint.
 */
@Entity
@Table(name = "charge")
public class Charge {

    public static final class State {
        /** Linked pending legs posted; awaiting fulfilment confirm / void / TB expiry. */
        public static final String HOLD = "HOLD";
        /** Fulfilment confirmed: post-pending chain committed the legs. */
        public static final String CONFIRMED = "CONFIRMED";
        /** Definitive rejection or pre-fulfilment cancel: legs released. */
        public static final String VOIDED = "VOIDED";
        /** TB auto-expired the pending (hold timed out); nothing was ever committed. */
        public static final String EXPIRED = "EXPIRED";
        private State() {}
    }

    @Id
    public UUID id;
    @Column(name = "wallet_id", nullable = false)
    public UUID walletId;
    @Column(name = "person_id", nullable = false)
    public UUID personId;
    /** The commercial reference (core sku_order id / sale ref) this charge funds. */
    @Column(name = "order_ref", nullable = false, length = 128)
    public String orderRef;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, columnDefinition = "CHAR(3)")
    public String currency;
    /** Merchandise value (net of VAT on the quoted sale price). */
    @Column(name = "merch_minor", nullable = false, precision = 20, scale = 0)
    public BigDecimal merchMinor;
    /** Platform fee charged inside the wallet book (0 when it lives only in the core GL). */
    @Column(name = "fee_minor", nullable = false, precision = 20, scale = 0)
    public BigDecimal feeMinor;
    /** VAT slice (rate-bucket snapshot lives with the fingerprint in core). */
    @Column(name = "tax_minor", nullable = false, precision = 20, scale = 0)
    public BigDecimal taxMinor;
    @Column(name = "total_minor", nullable = false, precision = 20, scale = 0)
    public BigDecimal totalMinor;
    /** SHA-256 of the quoted breakdown — EMI refuses charges whose fingerprint does not re-hash. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "price_fingerprint", nullable = false, columnDefinition = "CHAR(64)")
    public String priceFingerprint;
    @Column(name = "state", nullable = false, length = 12)
    public String state;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
    @Version
    public long version;

    /** The wallet leg always exists; fee/tax legs join only when non-zero (still linked). */
    public int legCount() {
        int n = 0;
        if (merchMinor.signum() > 0) n++;
        if (feeMinor.signum() > 0) n++;
        if (taxMinor.signum() > 0) n++;
        return n;
    }
}
