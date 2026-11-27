package com.fsp.emi.bill;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Exactly-once layer-3 record (plan §6): one row per outbound provider call
 * (service = EMI, kind = BILL_SUBMIT / BILL_PROBE), unique per (kind, idempotency_key).
 * The RECORDED row is written BEFORE the network call; the response state lands after.
 */
@Entity
@Table(name = "connector_call")
public class ConnectorCall {

    public static final class State {
        public static final String RECORDED = "RECORDED";
        public static final String SENT = "SENT";
        public static final String ACKED = "ACKED";
        public static final String PENDING = "PENDING";
        public static final String REJECTED = "REJECTED";
        public static final String FAILED = "FAILED";

        private State() {}
    }

    public static final String KIND_BILL_SUBMIT = "BILL_SUBMIT";
    public static final String KIND_BILL_PROBE = "BILL_PROBE";

    @Id
    public UUID id;
    @Column(name = "connector_code", nullable = false)
    public String connectorCode;
    @Column(name = "host_key", nullable = false)
    public String hostKey;
    @Column(name = "kind", nullable = false)
    public String kind;
    @Column(name = "idempotency_key", nullable = false)
    public String idempotencyKey;
    @Column(name = "rail_ref")
    public String railRef;
    @Column(name = "state", nullable = false)
    public String state;
    /** Rail failure-alphabet token (plan §3.2) when the call did not settle cleanly. */
    @Column(name = "failure_kind")
    public String failureKind;
    @Column(name = "failure_code")
    public String failureCode;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
    @Version
    public long version;
}
