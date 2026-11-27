package com.fsp.plugin.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Domain event trail (append-only; every record carries correlation_id — AGENTS §2f). */
@Entity
@Table(name = "inventory_events")
public class InventoryEvent {

    @Id
    public UUID id;
    @Column(name = "owner_tenant_id", nullable = false)
    public UUID ownerTenantId;
    @Column(name = "event_type", nullable = false)
    public String eventType;
    @Column(name = "subject_type", nullable = false)
    public String subjectType;
    @Column(name = "subject_id", nullable = false)
    public UUID subjectId;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(nullable = false)
    public String payload;
    @Column(name = "correlation_id", nullable = false)
    public String correlationId;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
}
