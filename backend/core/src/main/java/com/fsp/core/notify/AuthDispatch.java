package com.fsp.core.notify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Auth-plane dispatch queue (V30; docs/architecture/12-notifications-and-push.md §12.9).
 * Rows are written by the IdP inside its mutating transaction; this core sweep delivers
 * them through the SAME transports as F-12 notifications ({@code OutboundChannel} beans).
 * Strictly delivery-only: no consent gating (auth messages authenticate their recipient),
 * no dedupe, no inbox row.
 *
 * Secret hygiene: {@code text} is transient — it may embed a one-time code/link and is
 * NULLed on claim; contact is never stored, resolved from persons at send time.
 */
@Entity
@Table(name = "auth_dispatches", schema = "fsp_notifications")
public class AuthDispatch {

    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String MFA_LOGIN = "MFA_LOGIN";
    public static final String MFA_ENROLMENT = "MFA_ENROLMENT";

    @Id
    public UUID id;

    @Column(nullable = false, columnDefinition = "VARCHAR(16)")
    public String purpose;

    @Column(name = "person_id", nullable = false)
    public UUID personId;

    @Column(nullable = false, columnDefinition = "VARCHAR(4)")
    public String channel;

    @Column(nullable = false, columnDefinition = "VARCHAR(200)")
    public String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String text;

    @Column(name = "correlation_id", columnDefinition = "VARCHAR(64)")
    public String correlationId;

    @Column(nullable = false)
    public int attempts;

    @Column(columnDefinition = "VARCHAR(12)")
    public String via;

    @Column(name = "sent_at")
    public Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();
}
