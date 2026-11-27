package com.fsp.core.plugin.governance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read models for the platform plugin-governance screen (GAP-4). Manifests are summarized, never echoed. */
public final class PluginGovernanceDtos {

    private PluginGovernanceDtos() {}

    public record TypeView(UUID id, String code, String version, String name, String baseUrl, String status,
                           String manifestHash, int operationCount, List<String> permissions,
                           Instant registeredAt, Instant approvedAt, UUID pendingApprovalId) {}

    public record GateView(String gate, String status, Instant updatedAt) {}

    public record InstallationView(UUID id, UUID tenantId, String pluginCode, String pluginVersion, String status,
                                   String inheritanceOrigin, List<GateView> gates, boolean ready,
                                   Instant createdAt, Instant updatedAt, UUID pendingApprovalId) {}
}
