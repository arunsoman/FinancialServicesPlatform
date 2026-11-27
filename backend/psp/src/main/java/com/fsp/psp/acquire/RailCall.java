package com.fsp.psp.acquire;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Exactly-once rail-call ledger (V2 {@code rail_call}; mirror of EMI ConnectorCall):
 * every logical connector invocation owns one row keyed by a deterministic
 * {@code call_key}; a replay returns the recorded outcome instead of re-sending.
 */
@Entity
@Table(name = "rail_call")
public class RailCall {

    public static final String KIND_RAIL_AUTHORIZE = "RAIL_AUTHORIZE";
    public static final String KIND_STATEMENT_FETCH = "STATEMENT_FETCH";

    public static final String STATE_RECORDED = "RECORDED";
    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_ACKED = "ACKED";
    public static final String STATE_REJECTED = "REJECTED";
    public static final String STATE_FAILED = "FAILED";

    @Id
    public UUID id;

    @Column(nullable = false)
    public String kind;

    @Column(name = "call_key", nullable = false, unique = true)
    public String callKey;

    @Column(name = "connector_code", nullable = false)
    public String connectorCode;

    @Column(name = "merchant_id")
    public UUID merchantId;

    @Column(nullable = false)
    public String state = STATE_RECORDED;

    @Column(name = "rail_ref")
    public String railRef;

    @Column(name = "failure_kind")
    public String failureKind;

    @Column(name = "failure_code")
    public String failureCode;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
