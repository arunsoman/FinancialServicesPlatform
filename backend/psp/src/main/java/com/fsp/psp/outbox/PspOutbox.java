package com.fsp.psp.outbox;

import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * PSP transactional outbox (V1 {@code psp_outbox}) — every money/state mutation that
 * must be observed downstream lands here IN THE SAME DB TRANSACTION as the mutation
 * (exactly-once layer 4, plan §2.1). Dispatch claims rows once.
 */
@Entity
@Table(name = "psp_outbox")
public class PspOutbox {

    @Id
    public UUID id;

    @Column(name = "aggregate_id", nullable = false)
    public UUID aggregateId;

    @Column(nullable = false)
    public String kind;

    @Column(name = "payload_json", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    public String payloadJson;

    @Column(nullable = false)
    public int attempts = 0;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "claimed_at")
    public Instant claimedAt;
}
