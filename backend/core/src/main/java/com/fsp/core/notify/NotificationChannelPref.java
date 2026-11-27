package com.fsp.core.notify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-person outbound-channel consent (V29 — docs/architecture/12-notifications-and-push.md
 * §12.3/§12.4). Unlike push (a device registered by its owner), EMAIL/SMS write to the
 * person's profile contact — so they are opt-IN from the person's own profile, dormant by
 * default. {@code dead} records a definitive carrier/bounce verdict on the contact
 * (invalid mailbox, blocked number); the person can re-enable after fixing the profile.
 * Self-service rows only: read/written under the actor's home person id, never act-as.
 */
@Entity
@Table(name = "notification_channel_prefs", schema = "fsp_notifications")
@IdClass(NotificationChannelPrefId.class)
public class NotificationChannelPref {

    public static final String EMAIL = "EMAIL";
    public static final String SMS = "SMS";

    @Id
    @Column(name = "person_id")
    public UUID personId;

    @Id
    @Column(nullable = false)
    public String channel;

    /** The consent itself. Dormant-off by default. */
    @Column(nullable = false)
    public boolean enabled;

    /** Definitive bounce verdict — stops futile retries but keeps the consent visible. */
    @Column(nullable = false)
    public boolean dead;

    @Column(name = "created_at", insertable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    public Instant updatedAt;
}

class NotificationChannelPrefId implements Serializable {
    public UUID personId;
    public String channel;

    public NotificationChannelPrefId() {}

    public NotificationChannelPrefId(UUID personId, String channel) {
        this.personId = personId;
        this.channel = channel;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof NotificationChannelPrefId that)) return false;
        return personId.equals(that.personId) && channel.equals(that.channel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(personId, channel);
    }
}
