package com.fsp.emi.bill;

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
 * Bill-payment saga (V1 `bill_payment_saga`): one TigerBeetle pending transfer + one
 * row, in lockstep (plan §5.2). Never touches the customer's wallet balance until the
 * biller confirms — the pending authorization hold is the "space" (R3). Timeout AFTER
 * submission is never a failure: the hold parks in {@code PENDING_RECOGNITION} and only
 * a recon probe or a four-eyes force-void can resolve it.
 */
@Entity
@Table(name = "bill_payment_saga")
public class BillPaymentSaga {

    /** Saga state ladder (V1 CHECK constraint; plan §5.3 disposition space). */
    public static final class State {
        public static final String HOLD = "HOLD";
        public static final String SUBMITTED = "SUBMITTED";
        public static final String PENDING_RECOGNITION = "PENDING_RECOGNITION";
        public static final String CONFIRMED = "CONFIRMED";
        public static final String VOIDED = "VOIDED";
        public static final String EXPIRED = "EXPIRED";
        public static final String DISPUTED = "DISPUTED";

        private State() {}
    }

    @Id
    public UUID id;
    @Column(name = "person_id", nullable = false)
    public UUID personId;
    @Column(name = "wallet_id", nullable = false)
    public UUID walletId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, columnDefinition = "CHAR(3)")
    public String currency;
    /** NUMERIC(20,0) minor units — long in Java, string on the wire. */
    @Column(name = "amount_minor", nullable = false, precision = 20, scale = 0)
    public BigDecimal amountMinor;
    @Column(name = "biller_id", nullable = false)
    public UUID billerId;
    /** Customer's bill account number — PII: never logged, never in audit detail. */
    @Column(name = "account_ref", nullable = false)
    public String accountRef;
    @Column(name = "idempotency_key", nullable = false, unique = true)
    public String idempotencyKey;
    @Column(name = "state", nullable = false)
    public String state;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "reversal_class", nullable = false, columnDefinition = "CHAR(1)")
    public String reversalClass;
    /** Biller-side reference once accepted (null until submit acknowledgement). */
    @Column(name = "rail_ref")
    public String railRef;
    @Column(name = "submitted_at")
    public Instant submittedAt;
    @Column(name = "settled_at")
    public Instant settledAt;
    /** The single disposition deadline derived from fsp.emi.saga-grace-seconds. */
    @Column(name = "expires_at")
    public Instant expiresAt;
    @Column(name = "attempt", nullable = false)
    public int attempt;
    /** Failure alphabet token (plan §3.2) — e.g. CommUnreachable, HOLD_REJECTED. */
    @Column(name = "last_failure_kind")
    public String lastFailureKind;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
    @Version
    public long version;

    public long amount() { return amountMinor.longValueExact(); }
}
