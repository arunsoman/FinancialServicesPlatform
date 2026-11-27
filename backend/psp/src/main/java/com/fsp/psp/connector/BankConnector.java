package com.fsp.psp.connector;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * A bank connector profile row (V1 {@code bank_connector}; plan §3.4.3): a new bank is a
 * ROW, never code. Rail behaviour (message format, timeouts, cadence) is selected from
 * this row; credentials are referenced by env-var name only — never stored here.
 */
@Entity
@Table(name = "bank_connector")
public class BankConnector {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SANDBOX_CONFORMED = "SANDBOX_CONFORMED";
    public static final String STATUS_ENABLED = "ENABLED";

    @Id
    public UUID id;

    @Column(name = "bank_code", nullable = false, unique = true)
    public String bankCode;

    @Column(name = "bank_name", nullable = false)
    public String bankName;

    /** Rail taxonomy family (Stage-1 sealed taxonomy: CARD_SCHEME, BANK_TRANSFER, WALLET, ...). */
    @Column(name = "rail_family", nullable = false)
    public String railFamily;

    @Column(name = "transport_channel", nullable = false)
    public String transportChannel;

    /** Protocol discriminator the gateway dispatches on (e.g. REST_OPEN_BANKING, DEMO). */
    @Column(name = "message_format", nullable = false)
    public String messageFormat;

    @Column(name = "dictionary_version")
    public String dictionaryVersion;

    @Column(nullable = false)
    public String charset = "UTF-8";

    @Column(name = "connect_timeout_ms", nullable = false)
    public int connectTimeoutMs = 2000;

    @Column(name = "read_timeout_ms", nullable = false)
    public int readTimeoutMs = 8000;

    @Column(name = "overall_timeout_ms", nullable = false)
    public int overallTimeoutMs = 15000;

    /** Env-var NAME holding the endpoint credential — the secret itself never lands here. */
    @Column(name = "endpoint_credential_env_ref", nullable = false)
    public String endpointCredentialEnvRef;

    @Column(name = "settlement_window")
    public String settlementWindow;

    @Column(name = "statement_cadence")
    public String statementCadence;

    @Column(name = "idempotency_convention")
    public String idempotencyConvention;

    @Column(name = "profile_status", nullable = false)
    public String profileStatus = STATUS_DRAFT;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Version
    @Column(nullable = false)
    public long version;
}
