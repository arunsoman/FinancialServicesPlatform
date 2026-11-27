package com.fsp.psp.settlement;

import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** Settlement sweep batch — idempotent unit of the closing sweep (V1 table). */
@Entity
@Table(name = "settlement_batch")
public class SettlementBatch {

    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_SWEEPING = "SWEEPING";
    public static final String STATE_COMPLETED = "COMPLETED";
    public static final String STATE_FAILED = "FAILED";

    @Id
    public UUID id;

    @Column(name = "connector_code", nullable = false)
    public String connectorCode;

    @Column(nullable = false, columnDefinition = "CHAR(3)")
    @JdbcTypeCode(SqlTypes.CHAR)
    public String currency;

    @Column(name = "window_start", nullable = false)
    public Instant windowStart;

    @Column(name = "window_end", nullable = false)
    public Instant windowEnd;

    @Column(nullable = false)
    public String state = STATE_OPEN;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Version
    @Column(nullable = false)
    public long version;
}
