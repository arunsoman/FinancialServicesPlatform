package com.fsp.emi.bill;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Biller registry row (V1 `biller` + V3 `endpoint_url`). Per-biller reversal class
 * (plan §12.3): contract-silent billers default to C. Credentials stay env-name-only.
 */
@Entity
@Table(name = "biller")
public class Biller {

    public static final String CHANNEL_BILLER_REST = "BILLER_REST";

    @Id
    public UUID id;
    @Column(name = "code", nullable = false, unique = true)
    public String code;
    @Column(name = "name", nullable = false)
    public String name;
    @Column(name = "category", nullable = false)
    public String category;
    /** A/B/C reversal ladder (plan §12.3): A = contract reversal right, C = contract-silent default. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "reversal_class", nullable = false, columnDefinition = "CHAR(1)")
    public String reversalClass;
    @Column(name = "contract_reversal_window_seconds")
    public Long contractReversalWindowSeconds;
    @Column(name = "transport_channel", nullable = false)
    public String transportChannel;
    /** Env-var NAME holding the biller API credential — never the credential itself. */
    @Column(name = "endpoint_credential_env_ref", nullable = false)
    public String endpointCredentialEnvRef;
    @Column(name = "probe_supported", nullable = false)
    public boolean probeSupported;
    /** Base URL of the biller REST API (V3) — config data, not a secret. */
    @Column(name = "endpoint_url")
    public String endpointUrl;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
