package com.fsp.plugin.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Plugin-level idempotency records — unique on (person_id, key) per AGENTS §2e §9.1.
 *  The plugin's inbound actor is always a person from the actor assertion. */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    public UUID id;

    @Column(nullable = false)
    public UUID personId;

    @Column(nullable = false)
    public String requestKey;

    @Column(nullable = false)
    public String status;          // PENDING|DONE

    public Integer responseStatus;

    public String responseContentType;

    @Column(columnDefinition = "bytea")
    public byte[] responseBody;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    @Column(nullable = false)
    public Instant updatedAt = Instant.now();

    public IdempotencyRecord() {}
}
