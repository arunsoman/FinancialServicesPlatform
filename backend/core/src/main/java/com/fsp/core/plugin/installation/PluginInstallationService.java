package com.fsp.core.plugin.installation;

import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.plugin.events.PluginEventSubscriptionRepository;
import com.fsp.core.plugin.registry.PluginType;
import com.fsp.core.plugin.registry.PluginTypeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Plugin installation + effective-installation rule (§5.4):
 *  - Install: ONE STEP (AGENTS.md §2.1 easy install) — no four-eyes; the installation goes
 *    straight to PROVISIONING (PluginAutoInstaller). Registration installs on the platform root
 *    by itself, so a manual install is only needed for a narrower tenant.
 *  - Provisioning: the plugin reports its gates → ACTIVE when all gates are ACTIVE (~30 s)
 *  - Effective installation: walk hierarchy_path from target tenant up to root; first
 *    matching installation decides (DIRECT / inheritance policy / PROHIBITED override).
 */
@Slf4j
@Service
public class PluginInstallationService {

    private final PluginInstallationRepository installations;
    private final PluginInstallationGateRepository gates;
    private final PluginOptChoiceRepository optChoices;
    private final PluginTypeRepository types;
    private final TenantRepository tenants;
    private final PluginAutoInstaller installer;
    private final AuditService audit;
    private final OutboxService outbox;

    public PluginInstallationService(PluginInstallationRepository installations,
                                     PluginInstallationGateRepository gates,
                                     PluginOptChoiceRepository optChoices,
                                     PluginTypeRepository types, TenantRepository tenants,
                                     PluginAutoInstaller installer, AuditService audit,
                                     OutboxService outbox) {
        this.installations = installations;
        this.gates = gates;
        this.optChoices = optChoices;
        this.types = types;
        this.tenants = tenants;
        this.installer = installer;
        this.audit = audit;
        this.outbox = outbox;
    }

    /** Install a plugin on a tenant — one step, no approval: straight to PROVISIONING. */
    @Transactional
    public PluginInstallation install(UUID tenantId, String pluginCode, String pluginVersion,
                                      String config, Actor actor) {
        var registered = types.findByCodeAndStatus(pluginCode, "REGISTERED");
        if (registered.isEmpty()) {
            throw ApiException.badRequest("PLUGIN_TYPE_NOT_REGISTERED",
                "Plugin type %s is not registered".formatted(pluginCode));
        }
        PluginType type = registered.get(0);
        for (String live : List.of("ACTIVE", "PROVISIONING")) {
            if (!installations.findByPluginCodeAndTenantIdAndStatus(pluginCode, tenantId, live).isEmpty()) {
                throw ApiException.conflict("PLUGIN_INSTALLATION_ACTIVE",
                    "Plugin %s is already %s on tenant %s".formatted(pluginCode, live, tenantId));
            }
        }
        PluginInstallation inst = new PluginInstallation(
            UUID.randomUUID(), pluginCode, type.version, tenantId, "PENDING_APPROVAL",
            "INHERIT", "DIRECT", config);
        return installer.startProvisioning(inst, actor.personId(), "manual");
    }

    /** A PLUGIN_INSTALL approval opened before easy install, approved now: same one step. */
    @Transactional
    public PluginInstallation approve(UUID installationId, Actor checker) {
        PluginInstallation inst = installations.findById(installationId)
            .orElseThrow(() -> ApiException.notFound("INSTALLATION_NOT_FOUND", "Installation %s not found".formatted(installationId)));
        if (!"PENDING_APPROVAL".equals(inst.status)) {
            throw ApiException.conflict("INSTALLATION_NOT_PENDING",
                "Installation %s is not pending approval".formatted(installationId));
        }
        return installer.startProvisioning(inst, checker.personId(), "legacy-approval");
    }

    /** Plugin reports a readiness gate (machine actor). When all gates are ACTIVE, the
     *  installation flips to ACTIVE. */
    @Transactional
    public PluginInstallation ready(UUID installationId, String gate, String status, Actor actor) {
        PluginInstallation inst = installations.findById(installationId)
            .orElseThrow(() -> ApiException.notFound("INSTALLATION_NOT_FOUND", "Installation %s not found".formatted(installationId)));
        if (!"PROVISIONING".equals(inst.status)) {
            throw ApiException.conflict("INSTALLATION_NOT_PROVISIONING",
                "Installation %s is not in PROVISIONING state".formatted(installationId));
        }
        // Machine-only: client must match the plugin's client
        if (actor.type() != Actor.ActorType.MACHINE) {
            throw ApiException.forbidden("MACHINE_ONLY", "Only a plugin machine actor can report readiness");
        }
        if (!("plugin-" + inst.pluginCode).equals(actor.clientId())) {
            log.warn("plugin_ready_client_mismatch installationId={} client={}", installationId, actor.clientId());
            throw ApiException.forbidden("PLUGIN_CLIENT_MISMATCH", "only the plugin's own client may report its readiness");
        }

        var gateRow = gates.findById(new PluginInstallationGate.Id(installationId, gate));
        if (gateRow.isEmpty()) {
            throw ApiException.notFound("GATE_NOT_FOUND", "Gate %s not found on installation %s".formatted(gate, installationId));
        }
        gateRow.get().status = status;
        gateRow.get().updatedAt = Instant.now();
        gates.save(gateRow.get());

        boolean allActive = gates.findByInstallationId(installationId).stream()
            .allMatch(g -> "ACTIVE".equals(g.status));
        if (allActive) {
            inst.status = "ACTIVE";
            inst.updatedAt = Instant.now();
            installations.save(inst);

            audit.record(null, null, inst.tenantId, null,
                "PLUGIN_INSTALLATION_ACTIVE", "PLUGIN_INSTALLATION", inst.id.toString(),
                null, audit.hashState("plugin=" + inst.pluginCode + ";status=ACTIVE"), false);
            outbox.record("PluginInstallation", inst.id, "PluginInstallation.Active",
                "{\"plugin\":\"%s\",\"tenant\":\"%s\"}".formatted(inst.pluginCode, inst.tenantId));

            log.info("plugin_installation_active installationId={} plugin={} tenant={}", inst.id, inst.pluginCode, inst.tenantId);
        }
        return inst;
    }

    /** Suspend or deactivate an installation (four-eyes, person only). */
    @Transactional
    public PluginInstallation setStatus(UUID installationId, String status, Actor actor) {
        PluginInstallation inst = installations.findById(installationId)
            .orElseThrow(() -> ApiException.notFound("INSTALLATION_NOT_FOUND", "Installation %s not found".formatted(installationId)));
        if (!Set.of("SUSPENDED", "DEACTIVATED").contains(status)) {
            throw ApiException.badRequest("INVALID_STATUS", "status must be SUSPENDED or DEACTIVATED");
        }
        inst.status = status;
        inst.updatedAt = Instant.now();
        installations.save(inst);

        audit.record(actor.personId(), null, inst.tenantId, null,
            "PLUGIN_INSTALLATION_" + status, "PLUGIN_INSTALLATION", inst.id.toString(),
            null, audit.hashState("plugin=" + inst.pluginCode + ";status=" + status), false);
        log.info("plugin_installation_status installationId={} plugin={} status={} actor={}",
            inst.id, inst.pluginCode, status, actor.personId());
        return inst;
    }

    /** Effective installation for (pluginCode, tenantId): walk hierarchy_path from target
     *  tenant up to root; first matching installation decides. §5.4. */
    public EffectiveInstallation effective(String pluginCode, UUID tenantId) {
        Tenant target = tenants.findById(tenantId)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant %s not found".formatted(tenantId)));

        // Walk the hierarchy path from target up to root
        List<UUID> chain = new ArrayList<>();
        String path = target.hierarchyPath == null ? "/" + tenantId : target.hierarchyPath;
        for (String part : path.replaceFirst("^/", "").split("/")) {
            if (part.isBlank()) continue;
            try { chain.add(UUID.fromString(part)); } catch (IllegalArgumentException ignored) {}
        }
        // Reverse so we visit the target tenant first, then ancestors up to root
        Collections.reverse(chain);
        // Ensure the target itself is first
        if (!chain.contains(tenantId)) chain.add(0, tenantId);

        for (UUID nodeId : chain) {
            List<PluginInstallation> insts = installations.findByPluginCodeAndTenantIdAndStatus(
                pluginCode, nodeId, "ACTIVE");
            for (PluginInstallation inst : insts) {
                if (nodeId.equals(tenantId)) {
                    // Direct installation on the target tenant
                    return new EffectiveInstallation(inst, "DIRECT", true);
                }
                // Ancestor installation — check inheritance policy
                String policy = inst.inheritancePolicy;
                switch (policy) {
                    case "PROHIBITED" -> {
                        return new EffectiveInstallation(null, "PROHIBITED", false);
                    }
                    case "REQUIRED" -> {
                        return new EffectiveInstallation(inst, "INHERITED_FROM:" + nodeId, true);
                    }
                    case "INHERIT" -> {
                        return new EffectiveInstallation(inst, "INHERITED_FROM:" + nodeId, true);
                    }
                    case "OPT_OUT" -> {
                        // Applies only if no OPT_OUT recorded for this tenant or a node between
                        boolean optedOut = optChoices.findByTenantIdAndPluginCode(tenantId, pluginCode)
                            .map(c -> "OPT_OUT".equals(c.choice)).orElse(false);
                        if (!optedOut) {
                            return new EffectiveInstallation(inst, "INHERITED_FROM:" + nodeId, true);
                        }
                    }
                    case "OPT_IN" -> {
                        // Applies only if OPT_IN recorded for this tenant or a node between
                        boolean optedIn = optChoices.findByTenantIdAndPluginCode(tenantId, pluginCode)
                            .map(c -> "OPT_IN".equals(c.choice)).orElse(false);
                        if (optedIn) {
                            return new EffectiveInstallation(inst, "INHERITED_FROM:" + nodeId, true);
                        }
                    }
                }
            }
        }
        return new EffectiveInstallation(null, "NONE", false);
    }

    /**
     * Why a plugin does NOT apply to a tenant — for the gateway's 503 and its log line, so
     * "not ready" is never a mystery (staging 2026-11-25: the type was registered on deploy but
     * nobody had installed it anywhere, and the screen said "it finishes setting up on its own").
     * Looks at the target tenant and its ancestors, every status. Never used to grant anything.
     */
    public NotReady whyNot(String pluginCode, UUID tenantId, EffectiveInstallation effective) {
        if (!types.findByCodeAndStatus(pluginCode, "SUSPENDED").isEmpty()) {
            return new NotReady("PLUGIN_SUSPENDED", "The %s plugin is suspended platform-wide".formatted(pluginCode));
        }
        if (types.findByCodeAndStatus(pluginCode, "REGISTERED").isEmpty()) {
            return new NotReady("NOT_REGISTERED", "The %s plugin is not registered on this platform".formatted(pluginCode));
        }
        if ("PROHIBITED".equals(effective.origin())) {
            return new NotReady("PROHIBITED", "A parent organization prohibits the %s plugin here".formatted(pluginCode));
        }
        Tenant target = tenants.findById(tenantId).orElse(null);
        String path = target == null || target.hierarchyPath == null ? "/" + tenantId : target.hierarchyPath;
        Set<UUID> chain = new java.util.LinkedHashSet<>();
        chain.add(tenantId);
        for (String part : path.replaceFirst("^/", "").split("/")) {
            try { if (!part.isBlank()) chain.add(UUID.fromString(part)); } catch (IllegalArgumentException ignored) {}
        }
        List<PluginInstallation> found = installations.findByPluginCode(pluginCode).stream()
            .filter(i -> chain.contains(i.tenantId)).toList();
        if (found.isEmpty()) {
            return new NotReady("NOT_INSTALLED",
                "The %s plugin is not installed for this organization or any parent. A platform administrator installs it under Plugins (four-eyes).".formatted(pluginCode));
        }
        for (PluginInstallation i : found) {
            if ("PROVISIONING".equals(i.status)) {
                String pending = gates.findByInstallationId(i.id).stream()
                    .filter(g -> !"ACTIVE".equals(g.status)).map(g -> g.gate).sorted()
                    .collect(java.util.stream.Collectors.joining(","));
                return new NotReady("PROVISIONING",
                    "The %s plugin is still setting itself up (waiting on: %s)".formatted(pluginCode, pending));
            }
        }
        for (PluginInstallation i : found) {
            if ("PENDING_APPROVAL".equals(i.status)) {
                return new NotReady("PENDING_APPROVAL",
                    "Installing the %s plugin is waiting for a second administrator to approve it".formatted(pluginCode));
            }
        }
        for (PluginInstallation i : found) {
            if ("SUSPENDED".equals(i.status) || "DEACTIVATED".equals(i.status)) {
                return new NotReady("INSTALLATION_" + i.status,
                    "The %s plugin is %s for this organization".formatted(pluginCode, i.status.toLowerCase()));
            }
        }
        return new NotReady("NOT_ENABLED",
            "The %s plugin is installed above this organization but not enabled for it (%s)".formatted(pluginCode, effective.origin()));
    }

    /** Result of effective-installation resolution. */
    public record EffectiveInstallation(PluginInstallation installation, String origin, boolean applies) {}

    /** Why a plugin does not apply: a stable reason code plus a sentence a person can act on. */
    public record NotReady(String reason, String detail) {}
}
