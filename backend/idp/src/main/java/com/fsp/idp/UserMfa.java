package com.fsp.idp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The second-factor enrollment itself (V30; chapter 02 §7): the row IS the enrollment —
 * no row means MFA off. One channel per person, chosen at enrollment and proven by a
 * confirmation OTP sent through that very channel (possession proof).
 */
@Entity
@Table(name = "user_mfa")
public class UserMfa {

    public static final String EMAIL = "EMAIL";
    public static final String SMS = "SMS";

    @Id
    @Column(name = "person_id")
    public UUID personId;

    @Column(nullable = false, columnDefinition = "VARCHAR(4)")
    public String channel;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();
}
