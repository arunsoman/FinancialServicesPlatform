package com.fsp.core.plugin.installation;

import com.fsp.core.approval.ApprovalService;
import com.fsp.core.approval.ApprovalRequest;
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
 *  - Install: four-eyes gated, stores PENDING_APPROVAL + opens PLUGIN_INSTALL approval
 *  - Approve: PROVISIONING → plugin provisions → calls ready → ACTIVE when all gates ACTIVE
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
    private final ApprovalService approvals;
    private final AuditService audit;
    private final OutboxService outbox;

    public PluginInstallationService(PluginInstallationRepository installations,
                                     PluginInstallationGateRepository gates,
                                     PluginOptChoiceRepository optChoices,
                                     PluginTypeRepository types, TenantRepository tenants,
                                     ApprovalService approvals, AuditService audit,
                                     OutboxService outbox) {
        this.installations = installations;
        this.gates = gates;
        this.optChoices = optChoices;
        this.types = types;
        this.tenants = tenants;
        this.approvals = approvals;
        this.audit = audit;
        this.outbox = outbox;
    }

    /** Submit installation for a tenant (maker = caller). Four-eyes: stored PENDING_APPROVAL
     *  and a PLUGIN_INSTALL approval is opened. */
    @Transactional
    public PluginInstallation install(UUID tenantId, String pluginCode, String pluginVersion,
                                      String config, Actor actor) {
        // Verify the type is REGISTERED
        var registered = types.findByCodeAndStatus(pluginCode, "REGISTERED");
        if (registered.isEmpty()) {
            throw ApiException.badRequest("PLUGIN_TYPE_NOT_REGISTERED",
                "Plugin type %s is not REGISTERED (registration must be approved first)".formatted(pluginCode));
        }
        PluginType type = registered.get(0);

        // No duplicate ACTIVE installation
        var existing = installations.findByPluginCodeAndTenantIdAndStatus(pluginCode, tenantId, "ACTIVE");
        if (!existing.isEmpty()) {
            throw ApiException.conflict("PLUGIN_INSTALLATION_ACTIVE",
                "Plugin %s is already ACTIVE on tenant %s".formatted(pluginCode, tenantId));
        }

        PluginInstallation inst = new PluginInstallation(
            UUID.randomUUID(), pluginCode, type.version, tenantId, "PENDING_APPROVAL",
            "INHERIT", "DIRECT", config);
        installations.save(inst);

        // Open four-eyes approval
        ApprovalRequest row = approvals.open(
            tenantId, ApprovalRequest.OpType.PLUGIN_INSTALL, inst.id,
            actor, actor.personId(),
            "plugin.install %s@%s on tenant %s".formatted(pluginCode, type.version, tenantId),
            config);

        audit.record(actor.personId(), null, tenantId, null,
            "PLUGIN_INSTALL_SUBMITTED", "PLUGIN_INSTALLATION", inst.id.toString(),
            null, audit.hashState("plugin=" + pluginCode + ";tenant=" + tenantId + ";status=PENDING_APPROVAL"), false);
        outbox.record("PluginInstallation", inst.id, "PluginInstallation.Submitted",
            "{\"plugin\":\"%s\",\"tenant\":\"%s\",\"maker\":\"%s\"}".formatted(pluginCode, tenantId, actor.personId()));

        log.info("plugin_install_submitted plugin={} tenant={} maker={} installationId={}",
            pluginCode, tenantId, actor.personId(), inst.id);
        return inst;
    }

    /** Called by the PLUGIN_INSTALL approval handler after the checker approves. */
    @Transactional
    public PluginInstallation approve(UUID installationId, Actor checker) {
        PluginInstallation inst = installations.findById(installationId)
            .orElseThrow(() -> ApiException.notFound("INSTALLATION_NOT_FOUND", "Installation %s not found".formatted(installationId)));
        if (!"PENDING_APPROVAL".equals(inst.status)) {
            throw ApiException.conflict("INSTALLATION_NOT_PENDING",
                "Installation %s is not pending approval".formatted(installationId));
        }
        inst.status = "PROVISIONING";
        inst.updatedAt = Instant.now();
        installations.save(inst);

        // Provision default readiness gates (plugin will flip them to ACTIVE via ready())
        // GL_ACCOUNTS_ENSURED is optional: a plugin that moves no money has no GL wiring and
        // must not report it (KYC); money-moving plugins report it first (inventory).
        // A plugin gets the gate only when its manifest wires ledger MACHINE grants
        // (gl.account.* / je.post.immediate) — otherwise it could never reach ACTIVE.
        boolean ledgerCapable = ledgerCapable(inst.pluginCode);
        List<String> defaultGates = ledgerCapable
            ? List.of("PERMISSIONS_SEEDED", "CLIENT_ACTIVE", "SECRETS_PROVISIONED", "DEPENDENCIES_READY", "GL_ACCOUNTS_ENSURED")
            : List.of("PERMISSIONS_SEEDED", "CLIENT_ACTIVE", "SECRETS_PROVISIONED", "DEPENDENCIES_READY");
        for (String gate : defaultGates) {
            gates.save(new PluginInstallationGate(inst.id, gate, "PENDING"));
        }

        audit.record(null, checker.personId(), inst.tenantId, null,
            "PLUGIN_INSTALL_APPROVED", "PLUGIN_INSTALLATION", inst.id.toString(),
            null, audit.hashState("plugin=" + inst.pluginCode + ";status=PROVISIONING"), false);
        outbox.record("PluginInstallation", inst.id, "PluginInstallation.Approved",
            "{\"plugin\":\"%s\",\"tenant\":\"%s\",\"checker\":\"%s\"}".formatted(inst.pluginCode, inst.tenantId, checker.personId()));

        log.info("plugin_install_approved installationId={} plugin={} tenant={} checker={}",
            inst.id, inst.pluginCode, inst.tenantId, checker.personId());
        return inst;
    }

    /** Ledger-capable = manifest declares gl.account.* / je.post.immediate (MACHINE grants / ops). */
    private boolean ledgerCapable(String pluginCode) {
        return types.findByCodeAndStatus(pluginCode, "REGISTERED").stream()
            .findFirst()
            .map(t -> t.manifest != null && (t.manifest.contains("\"gl.account.") || t.manifest.contains("je.post.immediate")))
            .orElse(false);
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
