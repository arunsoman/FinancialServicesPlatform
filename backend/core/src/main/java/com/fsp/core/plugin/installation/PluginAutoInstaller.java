package com.fsp.core.plugin.installation;

import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.approval.ApprovalService;
import com.fsp.core.audit.AuditService;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.plugin.registry.PluginTypeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * EASY INSTALL (AGENTS.md §2.1, decided 2026-11-25): registering a plugin IS installing it —
 * one step, no four-eyes, no second day. The plugin is installed on the platform root with
 * INHERIT, so it applies to every organization below; it then provisions itself (its
 * ReadinessReporter reports the gates within ~30 s) and goes ACTIVE with nobody approving.
 *
 * A person's explicit decision is still respected: an installation a person SUSPENDED or
 * DEACTIVATED is left alone (reported as held_*), never silently re-enabled by a deploy.
 */
@Slf4j
@Component
public class PluginAutoInstaller {

    /** Platform root (seed V2). */
    public static final UUID PLATFORM_ROOT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final PluginInstallationRepository installations;
    private final PluginInstallationGateRepository gates;
    private final PluginTypeRepository types;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final OutboxService outbox;

    public PluginAutoInstaller(PluginInstallationRepository installations, PluginInstallationGateRepository gates,
                               PluginTypeRepository types, @Lazy ApprovalService approvals,
                               AuditService audit, OutboxService outbox) {
        this.installations = installations;
        this.gates = gates;
        this.types = types;
        this.approvals = approvals;
        this.audit = audit;
        this.outbox = outbox;
    }

    /**
     * Make sure {@code code} is installed on the platform root. Idempotent; called by every
     * registration (release or manual).
     *
     * @param by the person who registered it, or null for a release (provenance names the people)
     * @return installed | provisioning | active | held_suspended | held_deactivated
     */
    public String ensureOnPlatformRoot(String code, String version, UUID by, String via) {
        List<PluginInstallation> rows = installations.findByPluginCode(code).stream()
            .filter(i -> PLATFORM_ROOT.equals(i.tenantId)).toList();
        for (PluginInstallation i : rows) {
            if ("ACTIVE".equals(i.status)) {
                if (!version.equals(i.pluginVersion)) {
                    i.pluginVersion = version;
                    i.updatedAt = Instant.now();
                    installations.save(i);
                }
                return "active";
            }
        }
        for (PluginInstallation i : rows) {
            if ("PROVISIONING".equals(i.status)) return "provisioning";
        }
        for (PluginInstallation i : rows) {
            if ("SUSPENDED".equals(i.status) || "DEACTIVATED".equals(i.status)) {
                log.info("plugin_auto_install_held code={} installationId={} status={}", code, i.id, i.status);
                return "held_" + i.status.toLowerCase();
            }
        }
        PluginInstallation inst = rows.stream().filter(i -> "PENDING_APPROVAL".equals(i.status)).findFirst().orElse(null);
        if (inst != null) {
            // left over from the four-eyes era: the request is moot now
            approvals.withdrawPendingFor(ApprovalRequest.OpType.PLUGIN_INSTALL, inst.id, "installed on registration (" + via + ")");
            inst.pluginVersion = version;
        } else {
            inst = new PluginInstallation(UUID.randomUUID(), code, version, PLATFORM_ROOT, "PENDING_APPROVAL",
                "INHERIT", "DIRECT", null);
        }
        startProvisioning(inst, by, via);
        return "installed";
    }

    /** One step: the installation goes straight to PROVISIONING with its readiness gates. */
    public PluginInstallation startProvisioning(PluginInstallation inst, UUID by, String via) {
        inst.status = "PROVISIONING";
        inst.updatedAt = Instant.now();
        installations.save(inst);
        // GL_ACCOUNTS_ENSURED only for a plugin whose manifest wires ledger grants — a plugin that
        // moves no money (KYC) never reports it and would otherwise never reach ACTIVE.
        List<String> defaultGates = ledgerCapable(inst.pluginCode)
            ? List.of("PERMISSIONS_SEEDED", "CLIENT_ACTIVE", "SECRETS_PROVISIONED", "DEPENDENCIES_READY", "GL_ACCOUNTS_ENSURED")
            : List.of("PERMISSIONS_SEEDED", "CLIENT_ACTIVE", "SECRETS_PROVISIONED", "DEPENDENCIES_READY");
        for (String gate : defaultGates) {
            if (gates.findById(new PluginInstallationGate.Id(inst.id, gate)).isEmpty()) {
                gates.save(new PluginInstallationGate(inst.id, gate, "PENDING"));
            }
        }
        audit.record(by, null, inst.tenantId, null, "PLUGIN_INSTALLED", "PLUGIN_INSTALLATION", inst.id.toString(),
            null, audit.hashState("plugin=" + inst.pluginCode + ";version=" + inst.pluginVersion
                + ";tenant=" + inst.tenantId + ";via=" + via + ";status=PROVISIONING"), false);
        outbox.record("PluginInstallation", inst.id, "PluginInstallation.Installed",
            "{\"plugin\":\"%s\",\"tenant\":\"%s\",\"via\":\"%s\"}".formatted(inst.pluginCode, inst.tenantId, via));
        log.info("plugin_installed installationId={} plugin={} version={} tenant={} via={} by={}",
            inst.id, inst.pluginCode, inst.pluginVersion, inst.tenantId, via, by);
        return inst;
    }

    /** Ledger-capable = manifest declares gl.account.* / je.post.immediate (MACHINE grants / ops). */
    private boolean ledgerCapable(String pluginCode) {
        return types.findByCodeAndStatus(pluginCode, "REGISTERED").stream()
            .findFirst()
            .map(t -> t.manifest != null && (t.manifest.contains("\"gl.account.") || t.manifest.contains("je.post.immediate")))
            .orElse(false);
    }
}
