package com.fsp.core.notify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One notification row = one durable pointer a person was given (F-12: a checker is
 * pointed at pending four-eyes work without polling). The table is ALSO the delivery
 * queue for every outbound channel: the push cursor {@code pushed_at} (V23) plus the
 * email/SMS cursors {@code email_sent_at}/{@code sms_sent_at} (V29) are claimed
 * independently, in short per-row transactions, never inside the domain transaction.
 * Content is deliberately PII-free (AGENTS §2c / FAPI data minimization): generic
 * title/body, a type, and a link.
 */
@Entity
@Table(name = "notifications", schema = "fsp_notifications")
public class Notification {

    @Id
    public UUID id;

    @Column(name = "person_id", nullable = false)
    public UUID personId;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    /** Notification kind — Phase-0: APPROVAL_OPEN. */
    @Column(nullable = false)
    public String type;

    /** The canonical approval op this pointer is about (BANK_ACCOUNT_CREATE, USER_CREATE, …). */
    @Column(name = "op_type", nullable = false)
    public String opType;

    @Column(nullable = false)
    public String title;

    @Column(nullable = false)
    public String body;

    @Column(name = "resource_type", nullable = false)
    public String resourceType;

    @Column(name = "resource_id", nullable = false)
    public UUID resourceId;

    /** Portal-relative in-app path (e.g. /approvals) — navigation only, never authority. */
    @Column(nullable = false)
    public String link;

    @Column(name = "read_at")
    public Instant readAt;

    /** NULL until the push dispatcher resolved this row (delivered, unreachable, or skipped). */
    @Column(name = "pushed_at")
    public Instant pushedAt;

    /** Bounded retry: incremented on non-definitive provider failures; give up at 3. */
    @Column(name = "push_attempts", nullable = false)
    public int pushAttempts;

    /** WEBPUSH | APNS | FCM | NONE (no channel available / no credentials / no subscriptions). */
    @Column(name = "push_via")
    public String pushVia;

    /** Per-channel claim cursors — identical pattern to pushed_at (V29, email/SMS slice). */
    @Column(name = "email_sent_at")
    public Instant emailSentAt;

    @Column(name = "email_attempts", nullable = false)
    public int emailAttempts;

    /** SMTP | NONE (no pref / no profile address / channel disabled). */
    @Column(name = "email_via")
    public String emailVia;

    @Column(name = "sms_sent_at")
    public Instant smsSentAt;

    @Column(name = "sms_attempts", nullable = false)
    public int smsAttempts;

    /** Provider id | NONE. */
    @Column(name = "sms_via")
    public String smsVia;

    /** V38: hidden from the pending inbox until this instant (the person parked it). */
    @Column(name = "snoozed_until")
    public Instant snoozedUntil;

    /** V38: acted upon — the approval was decided (by anyone) or the notice was dismissed. */
    @Column(name = "resolved_at")
    public Instant resolvedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    public Instant createdAt;
}
