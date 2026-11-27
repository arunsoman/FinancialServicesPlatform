package com.fsp.psp.api;

import java.util.UUID;

/**
 * Verified person identity for an internal call (gateway re-issues these headers ONLY with
 * the internal credential — {@link PspIdentityFilter} refuses to trust them otherwise).
 * Zero-trust hop contract (platform AGENTS §1.4): identity headers + internal credential.
 */
public record PspActor(UUID personId, UUID tenantId, String username, String clientId) {}
