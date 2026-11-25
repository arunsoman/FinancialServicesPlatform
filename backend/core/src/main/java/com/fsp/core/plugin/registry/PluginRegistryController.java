package com.fsp.core.plugin.registry;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.FspOperation;
import com.fsp.core.rbac.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Plugin type registration (POST /v1/platform/plugins). Person-only; four-eyes gated.
 * The manifest is hashed + stored verbatim. On approval the type becomes REGISTERED.
 */
@Slf4j
@RestController
@RequestMapping("/v1/platform/plugins")
public class PluginRegistryController {

    private final PluginRegistryService service;
    private final PluginTypeRepository types;
    private final PermissionService permissions;
    private final PluginLifecycleService lifecycle;

    public PluginRegistryController(PluginRegistryService service, PluginTypeRepository types,
                                    PermissionService permissions, PluginLifecycleService lifecycle) {
        this.service = service;
        this.types = types;
        this.permissions = permissions;
        this.lifecycle = lifecycle;
    }

    @FspOperation(id = "plugin.register", component = "platform", permission = "plugin.register",
        risk = FspOperation.OperationRisk.FOUR_EYES,
        title = "Register a plugin type (four-eyes)",
        actors = {Actor.ActorType.PERSON})
    @PostMapping
    public PluginType register(HttpServletRequest request,
                               @RequestBody Map<String, Object> body) {
        Actor actor = actor(request);
        permissions.require(actor, "plugin.register", actor.activeTenantId());

        String code = asString(body.get("code"));
        String version = asString(body.get("version"));
        String baseUrl = asString(body.get("baseUrl"));
        String manifest = asString(body.get("manifest"));
        String manifestHash = asString(body.get("manifestHash"));
        if (code == null || version == null || baseUrl == null || manifest == null || manifestHash == null) {
            throw ApiException.badRequest("PLUGIN_REGISTER_INVALID",
                "code, version, baseUrl, manifest and manifestHash are required");
        }
        return service.register(code, version, baseUrl, manifest, manifestHash, actor);
    }

    // No direct "approve" endpoint: a registration is approved ONLY through the four-eyes approval
    // (PluginRegisterApprovalHandler → service.approve), never by a second route.

    @FspOperation(id = "plugin.suspend", component = "platform", permission = "plugin.register",
        risk = FspOperation.OperationRisk.FOUR_EYES,
        title = "Take a plugin out of service (four-eyes)",
        actors = {Actor.ActorType.PERSON})
    @PostMapping("/{code}/suspend")
    public Map<String, Object> suspend(HttpServletRequest request, @PathVariable("code") String code,
                                       @RequestBody(required = false) Map<String, Object> body) {
        Actor actor = actor(request);
        permissions.require(actor, "plugin.register", actor.activeTenantId());
        var row = lifecycle.submitSuspend(code, body == null ? null : asString(body.get("note")), actor);
        return Map.of("approvalId", row.id, "status", row.status, "operationType", row.operationType);
    }

    @FspOperation(id = "plugin.resume", component = "platform", permission = "plugin.register",
        risk = FspOperation.OperationRisk.FOUR_EYES,
        title = "Put a suspended plugin back in service (four-eyes)",
        actors = {Actor.ActorType.PERSON})
    @PostMapping("/{code}/resume")
    public Map<String, Object> resume(HttpServletRequest request, @PathVariable("code") String code,
                                      @RequestBody(required = false) Map<String, Object> body) {
        Actor actor = actor(request);
        permissions.require(actor, "plugin.register", actor.activeTenantId());
        var row = lifecycle.submitResume(code, body == null ? null : asString(body.get("note")), actor);
        return Map.of("approvalId", row.id, "status", row.status, "operationType", row.operationType);
    }

    @FspOperation(id = "plugin.type.get", component = "platform", permission = "plugin.manage",
        risk = FspOperation.OperationRisk.READ, title = "Read a plugin type",
        actors = {Actor.ActorType.PERSON})
    @GetMapping("/{code}/{version}")
    public PluginType get(@PathVariable("code") String code, @PathVariable("version") String version) {
        return service.find(code, version);
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
