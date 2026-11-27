package com.fsp.emi.bill;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only saga audit trail (V3 `saga_event`, platform §2.5). Every state transition,
 * probe outcome and four-eyes decision lands here; rows are never updated or deleted.
 */
@Entity
@Table(name = "saga_event")
public class SagaEvent {

    @Id
    public UUID id;
    @Column(name = "saga_id", nullable = false)
    public UUID sagaId;
    @Column(name = "event_type", nullable = false)
    public String eventType;
    @Column(name = "from_state")
    public String fromState;
    @Column(name = "to_state")
    public String toState;
    @Column(name = "actor_person_id")
    public UUID actorPersonId;
    @Column(name = "checker_person_id")
    public UUID checkerPersonId;
    /** Reason code / token only — account numbers and PII are forbidden here. */
    @Column(name = "detail")
    public String detail;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
