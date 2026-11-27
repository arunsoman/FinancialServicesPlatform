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
 * One password-reset request (V30; chapter 02 §7). The one-time token itself never rests:
 * only its SHA-256 hash is stored. Single-use; one live reset per person (partial unique
 * index in V30) — a new request supersedes any previous pending row. Confirm attempts are
 * capped; superseded/expired rows simply stop matching.
 */
@Entity
@Table(name = "password_resets")
public class PasswordReset {

    @Id
    public UUID id;

    @Column(name = "person_id", nullable = false)
    public UUID personId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, columnDefinition = "CHAR(64)")
    public String tokenHash;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(name = "consumed_at")
    public Instant consumedAt;

    @Column(nullable = false)
    public int attempts;

    /** V36: RESET (self-service) | ACTIVATION (first password, proves the primary contact). */
    @Column(nullable = false)
    public String purpose = "RESET";

    /** V36: EMAIL (link token) | SMS (one-time code bound to the person). */
    @Column(nullable = false)
    public String channel = "EMAIL";

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();
}
