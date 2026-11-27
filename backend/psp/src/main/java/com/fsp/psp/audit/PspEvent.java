package com.fsp.psp.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Append-only audit event at the PSP boundary (platform §7.1 PSP copy; V2 {@code psp_event}). */
@Entity
@Table(name = "psp_event")
public class PspEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "acquisition_id")
    public UUID acquisitionId;

    @Column(name = "merchant_id")
    public UUID merchantId;

    @Column(nullable = false)
    public String kind;

    @Column(name = "from_state")
    public String fromState;

    @Column(name = "to_state")
    public String toState;

    @Column
    public String actor;

    @Column
    public String detail;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
