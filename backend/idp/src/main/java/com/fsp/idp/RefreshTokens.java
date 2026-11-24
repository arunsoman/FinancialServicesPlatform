package com.fsp.idp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Refresh tokens for the BFF's sign-ins (FAPI 2.0 authorization code flow) — V43
 * {@code idp_refresh_tokens}. The value is 256 random bits handed to the BFF once; only its
 * SHA-256 is stored, so a database read never yields a usable token. Each token is bound to the
 * client that received it and to the DPoP key (jkt) its access tokens are bound to. Tokens are
 * never rotated (FAPI 2.0 §5.3.2.1) and are revoked at logout (RFC 7009).
 */
@Slf4j
@Component
public class RefreshTokens {

    /** One stored token, as the refresh grant needs it. */
    public record Stored(UUID id, UUID personId, UUID tenantId, String clientId, String jkt,
                         Instant expiresAt, Instant revokedAt) {}

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokens(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Mint a token for (person, tenant, client, jkt) valid for {@code ttlSeconds}; returns the opaque value. */
    public String issue(UUID personId, UUID tenantId, String clientId, String jkt, long ttlSeconds) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.update("""
            INSERT INTO idp_refresh_tokens (id, token_hash, person_id, tenant_id, client_id, jkt, issued_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, now(), ?)""",
            UUID.randomUUID(), hash(value), personId, tenantId, clientId, jkt,
            Timestamp.from(Instant.now().plusSeconds(ttlSeconds)));
        return value;
    }

    public Optional<Stored> find(String value) {
        if (value == null || value.isBlank() || value.length() > 128) return Optional.empty();
        List<Stored> rows = jdbc.query("""
            SELECT id, person_id, tenant_id, client_id, jkt, expires_at, revoked_at
            FROM idp_refresh_tokens WHERE token_hash = ?""",
            (rs, i) -> new Stored(rs.getObject("id", UUID.class), rs.getObject("person_id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getString("client_id"), rs.getString("jkt"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()),
            hash(value));
        return rows.stream().findFirst();
    }

    public void touch(UUID id) {
        jdbc.update("UPDATE idp_refresh_tokens SET last_used_at = now() WHERE id = ?", id);
    }

    /** Revoke a token presented by {@code clientId}; a token of another client is left alone. */
    public boolean revoke(String value, String clientId, String reason) {
        int n = jdbc.update("""
            UPDATE idp_refresh_tokens SET revoked_at = now(), revoke_reason = ?
            WHERE token_hash = ? AND client_id = ? AND revoked_at IS NULL""",
            reason, hash(value), clientId);
        return n > 0;
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
