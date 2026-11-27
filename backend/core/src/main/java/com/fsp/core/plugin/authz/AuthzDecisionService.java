package com.fsp.core.plugin.authz;

import com.fsp.core.actas.ActAsService;
import com.fsp.core.common.RequestCorrelation;

import com.fsp.core.rbac.PermissionService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Authorization decision service for the gateway pre-check (plugin-platform-plan §6).
 * The gateway calls POST /internal/authz/decide before forwarding a person→plugin request.
 * Core re-decides using person RBAC at the target tenant — never cached (revocation must
 * hit the next call, invariant I7).
 */
@Slf4j
@Service
public class AuthzDecisionService {

    private final PermissionService permissions;
    private final TenantRepository tenants;
    private final ActAsService actAs;

    public AuthzDecisionService(PermissionService permissions, TenantRepository tenants, ActAsService actAs) {
        this.permissions = permissions;
        this.tenants = tenants;
        this.actAs = actAs;
    }

    public Decision decide(UUID personId, String username, UUID activeTenantId,
                           String permission, UUID targetTenantId, String operationId) {
        return decide(personId, username, activeTenantId, permission, targetTenantId, operationId, null);
    }

    /**
     * With an act-as session the person is decided AS the acted-as tenant: the session must be
     * live and theirs, the target must be inside the acted-as subtree, and the person's
     * permissions are capped by its ceiling (PermissionService, ACTING).
     */
    public Decision decide(UUID personId, String username, UUID activeTenantId,
                           String permission, UUID targetTenantId, String operationId, String actSessionId) {
        Actor actor = new Actor(personId, username, activeTenantId);
        if (actSessionId != null && !actSessionId.isBlank()) {
            ActAsService.Live live = actAs.live(actSessionId, personId);
            if (live == null) {
                log.warn("authz_act_as_expired personId={} session={} op={}", personId, actSessionId, operationId);
                return new Decision(false, "ACT_AS_EXPIRED", permission);
            }
            RequestCorrelation.setHomeTenantId(live.homeTenantId());
            RequestCorrelation.setActSessionId(live.id());
            actor = new Actor(personId, username, live.targetTenantId(), Actor.ActorType.ACTING, null);
        }
        Tenant target = tenants.findById(targetTenantId)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND",
                "Target tenant %s not found".formatted(targetTenantId)));

        boolean reachable = isReachable(actor.activeTenantId(), targetTenantId);
        if (!reachable) {
            log.warn("authz_not_reachable personId={} targetTenant={} op={}",
                personId, targetTenantId, operationId);
            return new Decision(false, "NOT_REACHABLE", permission);
        }

        try {
            permissions.require(actor, permission, targetTenantId);
            log.info("authz_allowed personId={} permission={} targetTenant={} op={}",
                personId, permission, targetTenantId, operationId);
            return new Decision(true, "OK", permission);
        } catch (ApiException e) {
            if ("PERMISSION_DENIED".equals(e.getCode())) {
                log.warn("authz_denied personId={} permission={} targetTenant={} op={}",
                    personId, permission, targetTenantId, operationId);
                return new Decision(false, "PERMISSION_DENIED", permission);
            }
            if ("MAKER_CANNOT_APPROVE".equals(e.getCode())) {
                log.warn("authz_maker_cannot_approve personId={} op={}", personId, operationId);
                return new Decision(false, "MAKER_CANNOT_APPROVE", permission);
            }
            throw e;
        }
    }

    private boolean isReachable(UUID actorTenantId, UUID targetTenantId) {
        // The actor's active tenant must be the target or an ancestor of it
        Tenant target = tenants.findById(targetTenantId).orElse(null);
        if (target == null) return false;
        String path = target.hierarchyPath == null ? "/" + targetTenantId : target.hierarchyPath;
        for (String part : path.replaceFirst("^/", "").split("/")) {
            if (part.isBlank()) continue;
            try {
                if (UUID.fromString(part).equals(actorTenantId)) return true;
            } catch (IllegalArgumentException ignored) {}
        }
        return actorTenantId.equals(targetTenantId);
    }

    public record Decision(boolean allow, String reason, String permission) {}
}
