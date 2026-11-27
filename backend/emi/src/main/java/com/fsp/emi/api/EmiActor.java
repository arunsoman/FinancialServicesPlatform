package com.fsp.emi.api;

import java.util.UUID;

/**
 * Verified person identity for an internal call (gateway re-issues these headers ONLY with
 * the internal credential — {@link EmiIdentityFilter} refuses to trust them otherwise).
 * Zero-trust hop contract (platform AGENTS §1.4): identity headers + internal credential.
 */
public record EmiActor(UUID personId, UUID tenantId, String username, String clientId) {}
