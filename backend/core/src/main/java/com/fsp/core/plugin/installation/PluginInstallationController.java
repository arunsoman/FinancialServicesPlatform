package com.fsp.core.plugin.installation;

import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.FspOperation;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.PermissionService;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Plugin installation per tenant (POST /v1/tenants/{id}/plugins), readiness reporting
 * by the plugin (POST /v1/plugins/installations/{id}/ready), and effective-installation
 * lookup (GET /v1/plugins/{code}/installations/effective).
 */
@Slf4j
@RestController
public class PluginInstallationController {

    private final PluginInstallationService service;
    private final PermissionService permissions;
    private final TenantRepository tenants;

    public PluginInstallationController(PluginInstallationService service,
                                        PermissionService permissions, TenantRepository tenants) {
        this.service = service;
        this.permissions = permissions;
        this.tenants = tenants;
    }

    /** Submit installation of a plugin on a tenant. Four-eyes gated (person only). */
    @FspOperation(id = "plugin.install", component = "platform", permission = "plugin.install",
        risk = FspOperation.OperationRisk.FOUR_EYES,
        title = "Install a plugin on a tenant (four-eyes)",
        actors = {Actor.ActorType.PERSON})
    @PostMapping("/v1/tenants/{tenantId}/plugins")
    public PluginInstallation install(HttpServletRequest request,
                                      @PathVariable("tenantId") UUID tenantId,
                                      @RequestBody Map<String, Object> body) {
        Actor actor = actor(request);
        permissions.require(actor, "plugin.install", tenantId);

        String pluginCode = asString(body.get("code"));
        String pluginVersion = asString(body.get("version"));
        String config = asString(body.get("config"));
        if (pluginCode == null || pluginVersion == null) {
            throw ApiException.badRequest("PLUGIN_INSTALL_INVALID",
                "code and version are required");
        }
        tenants.findById(tenantId).orElseThrow(() ->
            ApiException.notFound("TENANT_NOT_FOUND", "Tenant %s not found".formatted(tenantId)));
        return service.install(tenantId, pluginCode, pluginVersion, config, actor);
    }

    // No direct "approve" endpoint: an installation is approved ONLY through the four-eyes approval
    // (PluginInstallApprovalHandler → service.approve), never by a second route.

    /** Plugin reports a readiness gate (machine only). */
    @FspOperation(id = "plugin.installation.ready", component = "platform", permission = "plugin.manage",
        risk = FspOperation.OperationRisk.WRITE,
        title = "Report plugin installation readiness gate (machine)",
        actors = {Actor.ActorType.MACHINE})
    @PostMapping("/v1/plugins/installations/{installationId}/ready")
    public PluginInstallation ready(@PathVariable("installationId") UUID installationId,
                                    @RequestBody Map<String, Object> body,
                                    HttpServletRequest request) {
        Actor actor = actor(request);
        String gate = asString(body.get("gate"));
        String status = asString(body.get("status"));
        if (gate == null || status == null) {
            throw ApiException.badRequest("PLUGIN_READY_INVALID", "gate and status are required");
        }
        return service.ready(installationId, gate, status, actor);
    }

    /** Suspend or deactivate an installation (four-eyes, person only). */
    @FspOperation(id = "plugin.installation.manage", component = "platform", permission = "plugin.manage",
        risk = FspOperation.OperationRisk.FOUR_EYES,
        title = "Suspend/deactivate a plugin installation (four-eyes)",
        actors = {Actor.ActorType.PERSON})
    @PatchMapping("/v1/plugins/installations/{installationId}/status")
    public PluginInstallation setStatus(@PathVariable("installationId") UUID installationId,
                                        @RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        Actor actor = actor(request);
        String status = asString(body.get("status"));
        if (status == null) {
            throw ApiException.badRequest("PLUGIN_STATUS_INVALID", "status is required");
        }
        return service.setStatus(installationId, status, actor);
    }

    /** Effective installation for (pluginCode, tenantId): walks hierarchy_path and applies
     *  inheritance policy (DIRECT / INHERIT / REQUIRED / OPT_OUT / OPT_IN / PROHIBITED). */
    @FspOperation(id = "plugin.installation.effective", component = "platform", permission = "plugin.manage",
        risk = FspOperation.OperationRisk.READ,
        title = "Resolve effective plugin installation for a tenant",
        actors = {Actor.ActorType.MACHINE, Actor.ActorType.PERSON})
    @GetMapping("/v1/plugins/{pluginCode}/installations/effective")
    public PluginInstallationService.EffectiveInstallation effective(
            @PathVariable("pluginCode") String pluginCode,
            @RequestParam("tenantId") UUID tenantId,
            HttpServletRequest request) {
        Actor actor = actor(request);
        if (actor.isHumanPlane()) {
            permissions.require(actor, "plugin.manage", actor.activeTenantId());
        }
        return service.effective(pluginCode, tenantId);
    }

    private static Actor actor(HttpServletRequest request) {
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (actor == null) throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        return actor;
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
