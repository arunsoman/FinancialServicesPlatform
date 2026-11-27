package com.fsp.core.plugin.authz;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.FspOperation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Authorization decision endpoint for the gateway pre-check (plugin-platform-plan §6).
 * Internal hop only: gateway calls POST /internal/authz/decide before forwarding a
 * person→plugin request. Core is the only PDP; this endpoint is uncached (revocation
 * must hit the next call, invariant I7).
 */
@Slf4j
@RestController
public class AuthzDecisionController {

    private final AuthzDecisionService service;

    public AuthzDecisionController(AuthzDecisionService service) {
        this.service = service;
    }

    // Internal hop only (credential-gated by ActorContextFilter); not a catalog business operation.
    @PostMapping("/internal/authz/decide")
    public Map<String, Object> decide(HttpServletRequest request,
                                      @RequestBody Map<String, Object> body) {
        Actor actor = actor(request);

        String permission = asString(body.get("permission"));
        UUID targetTenantId = parseUuid(body.get("tenantId"), "tenantId");
        String operationId = asString(body.get("operationId"));

        if (permission == null || permission.isBlank()) {
            throw ApiException.badRequest("AUTHZ_INVALID", "permission is required");
        }
        if (targetTenantId == null) {
            throw ApiException.badRequest("AUTHZ_INVALID", "tenantId is required");
        }

        // act-as (plans/act-as-tenant-plan.md): the gateway passes the session it resolved; core
        // re-verifies it and decides the person AS the acted-as tenant (ceiling-capped)
        String actSessionId = asString(body.get("actSessionId"));
        AuthzDecisionService.Decision decision = service.decide(
            actor.personId(), actor.username(), actor.activeTenantId(),
            permission, targetTenantId, operationId, actSessionId);

        return Map.of(
            "allow", decision.allow(),
            "reason", decision.reason(),
            "permission", decision.permission(),
            "personId", actor.personId(),
            "targetTenantId", targetTenantId);
    }

    private static Actor actor(HttpServletRequest request) {
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (actor == null) throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        return actor;
    }

    private static UUID parseUuid(Object v, String field) {
        if (v == null) return null;
        try { return UUID.fromString(String.valueOf(v)); }
        catch (IllegalArgumentException e) {
            throw ApiException.badRequest("AUTHZ_INVALID", field + " must be a UUID");
        }
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
