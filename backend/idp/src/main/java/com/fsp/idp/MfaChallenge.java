package com.fsp.idp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One MFA verification challenge (V30; chapter 02 §7). The OTP never rests in clear —
 * only its SHA-256 hash. Per-challenge attempt cap (≤3, then consumed); issued per login
 * attempt with a cooldown so a wrong-party login cannot spam the person's phone/mail.
 */
@Entity
@Table(name = "mfa_challenges")
public class MfaChallenge {

    public static final String LOGIN = "LOGIN";
    public static final String ENROLMENT = "ENROLMENT";

    @Id
    public UUID id;

    @Column(name = "person_id", nullable = false)
    public UUID personId;

    @Column(nullable = false, columnDefinition = "VARCHAR(12)")
    public String purpose;

    @Column(nullable = false, columnDefinition = "VARCHAR(4)")
    public String channel;

    @Column(name = "code_hash", nullable = false, columnDefinition = "CHAR(64)")
    @JdbcTypeCode(SqlTypes.CHAR)
    public String codeHash;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(name = "consumed_at")
    public Instant consumedAt;

    @Column(nullable = false)
    public int attempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();
}
