package com.fsp.idp;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * The IdP's half of the append-only audit trail (core {@code audit_log}, AGENTS §2.5): every
 * credential-lifecycle step the IdP decides — an activation issued, completed or refused, a
 * password reset requested or completed — is written in the SAME transaction as the change it
 * records, so the change and its evidence commit together or not at all.
 *
 * Same row shape as core's AuditService: ids + a SHA-256 of a non-secret state snapshot. Never
 * a token, code, password, link or contact value (AGENTS §1.5). The person acting on their own
 * credential is the actor; system-issued steps (an activation sent after a checker's approval)
 * carry no actor and the client id {@code fsp-idp}.
 */
@Component
public class IdpAudit {

    static final String CLIENT = "fsp-idp";
    private final JdbcTemplate jdbc;

    public IdpAudit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param actorId  the person acting (themselves), or null for a system step
     * @param tenantId the person's organization (audit is read per tenant)
     * @param action   e.g. ACTIVATION_ISSUED
     * @param personId the credential's owner
     * @param state    a NON-secret snapshot (channel, outcome, reason) — hashed, never stored raw
     */
    public void record(UUID actorId, UUID tenantId, String action, UUID personId, String state) {
        String trace = MDC.get("traceId");
        jdbc.update("INSERT INTO audit_log (actor_id, checker_id, tenant_id, book_id, action_type, resource_type, "
                + "resource_id, before_state_hash, after_state_hash, correlation_id, client_id, simulated, timestamp_utc) "
                + "VALUES (?, NULL, ?, NULL, ?, 'PERSON', ?, NULL, ?, ?, ?, false, ?)",
            actorId, tenantId, action, personId.toString(), IdpAuthService.sha256Hex(state),
            trace == null || trace.isBlank() ? UUID.randomUUID().toString() : trace, CLIENT,
            Timestamp.from(Instant.now()));
    }
}
