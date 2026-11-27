package com.fsp.core.notify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * One registered destination per person: a Web Push subscription (endpoint + key material),
 * or an APNs / FCM device token. Status DEAD = the provider definitively rejected this
 * destination (gone / unregistered) — the dispatcher flips it once and never retries it.
 */
@Entity
@Table(name = "push_subscriptions", schema = "fsp_notifications")
public class PushSubscription {

    @Id
    public UUID id;

    @Column(name = "person_id", nullable = false)
    public UUID personId;

    /** The person's HOME tenant (actor.activeTenantId is ignored — act-as never re-bases devices). */
    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    /** WEBPUSH | APNS | FCM. */
    @Column(nullable = false)
    public String platform;

    /** WEBPUSH push-service URL (unique). Empty string for APNS/FCM. */
    @Column
    public String endpoint;

    /** APNs / FCM device token (unique). Empty string for WEBPUSH. */
    @Column
    public String token;

    /** WEBPUSH RFC 8291 client public key (base64url). */
    @Column(name = "p256dh")
    public String p256dh;

    /** WEBPUSH auth secret (base64url). */
    @Column
    public String auth;

    @Column(name = "user_agent")
    public String userAgent;

    @Column(nullable = false)
    public String status;

    /** Optimistic lock (AGENTS §2.8): re-subscribe writes compare-and-swap on this row
     *  so a concurrent retire/refresh fails loudly instead of lost-updating (V31). */
    @Version
    @Column(nullable = false)
    public long version;

    @Column(name = "created_at", insertable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "last_push_at")
    public Instant lastPushAt;
}
