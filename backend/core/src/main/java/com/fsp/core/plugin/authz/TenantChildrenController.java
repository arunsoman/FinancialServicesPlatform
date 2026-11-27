package com.fsp.core.plugin.authz;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.FspOperation;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Tenant children creation endpoint (§12.5 step 4 of plugin-platform-plan).
 * POST /v1/tenants/{parentId}/children — creates tenant PENDING + applicant admin +
 * suspended books atomically. Called by plugins on behalf of a person (DELEGATED actor).
 * This is the core part of the onboarding saga; the plugin continues with KYC case,
 * screening, approval, and verification.
 */
@Slf4j
@RestController
public class TenantChildrenController {

    private final TenantChildrenService service;
    private final com.fsp.core.rbac.PermissionService permissions;
    private final com.fsp.core.identity.IdpInternalClient idp;

    public TenantChildrenController(TenantChildrenService service, com.fsp.core.rbac.PermissionService permissions,
                                    com.fsp.core.identity.IdpInternalClient idp) {
        this.service = service;
        this.permissions = permissions;
        this.idp = idp;
    }

    @FspOperation(id = "tenant.child.create", component = "tenant", permission = "org.create",
        risk = FspOperation.OperationRisk.WRITE,
        title = "Create child tenant (onboarding saga step 4, delegated)",
        actors = {Actor.ActorType.DELEGATED, Actor.ActorType.PERSON})
    @PostMapping("/v1/tenants/{parentId}/children")
    public Map<String, Object> createChild(@PathVariable("parentId") UUID parentId,
                              @RequestBody Map<String, Object> body,
                              HttpServletRequest request) {
        Actor actor = actor(request);
        // The interceptor resolves {tenantId} only — decide on the PARENT explicitly (reachability +
        // permission at the node the child is created under), for the person AND the plugin client.
        permissions.require(actor, "org.create", parentId);
        if (actor.type() == Actor.ActorType.DELEGATED) {
            permissions.requireClient(actor.clientId(), "org.create", parentId);
        }

        String legalName = asString(body.get("legalName"));
        String subType = asString(body.get("subType"));
        String adminUsername = asString(body.get("adminUsername"));
        String adminFullName = asString(body.get("adminFullName"));

        if (legalName == null || legalName.isBlank()) {
            throw ApiException.badRequest("TENANT_FIELDS", "legalName is required");
        }
        if (adminUsername == null || adminUsername.isBlank()) {
            throw ApiException.badRequest("TENANT_FIELDS", "adminUsername is required");
        }

        var created = service.createChild(parentId, legalName, subType, adminUsername, adminFullName,
            invitee(body.get("admin")), invitee(body.get("checker")), actor);
        // V36: the tx committed — now (never inside it) ask the IdP to send each activation.
        // A delivery failure does not undo the org: the maker re-sends from the case.
        for (UUID personId : created.invitedPersonIds()) {
            try {
                idp.triggerActivation(personId);
            } catch (RuntimeException e) {
                log.warn("tenant_child_activation_send_failed tenantId={} personId={} reason={}",
                    created.tenant().id, personId, e.getClass().getSimpleName());
            }
        }
        Tenant t = created.tenant();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", t.id);
        out.put("parentTenantId", t.parentTenantId);
        out.put("legalName", t.legalName);
        out.put("type", t.type);
        out.put("subType", t.subType);
        out.put("status", t.status);
        out.put("verificationStatus", t.verificationStatus);
        // V36: [admin, checker] — the plugin re-sends their activation by these ids
        out.put("invitedPersonIds", created.invitedPersonIds());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static TenantChildrenService.Invitee invitee(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Map<String, Object> v = (Map<String, Object>) m;
        return new TenantChildrenService.Invitee(asString(v.get("fullName")), asString(v.get("email")),
            asString(v.get("phone")), asString(v.get("primaryContact")));
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
