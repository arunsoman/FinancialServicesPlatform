package com.fsp.core.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;
import com.fsp.core.rbac.PermissionService;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.UUID;

/**
 * Operation-catalog enforcement (FAPI2 guide §3.5): after Spring selects the handler and
 * BEFORE controller execution, resolve the {@link FspOperation} declaration, evaluate the
 * required permission on the resolved tenant, and record the decision.
 * The application service still re-checks business rules + four-eyes (defense in depth).
 *
 * Actor-plane gating (plugin-platform-plan §6):
 *  - op.actors() defaults to {PERSON} — existing declarations are unchanged
 *  - MACHINE actors are checked against client_grants (not person RBAC)
 *  - DELEGATED actors are checked against BOTH person RBAC and client grant
 *  - ACTING (act-as) is admitted wherever PERSON is — it is the same signed-in person — and is
 *    checked against person RBAC, which PermissionService caps by the target tenant's ceiling
 */
@Slf4j
public class FspOperationInterceptor implements HandlerInterceptor {

    private final PermissionService permissions;

    public FspOperationInterceptor(PermissionService permissions) {
        this.permissions = permissions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof org.springframework.web.method.HandlerMethod hm)) {
            return true; // static resources etc.
        }
        FspOperation op = hm.getMethodAnnotation(FspOperation.class);
        if (op == null) op = hm.getBeanType().getAnnotation(FspOperation.class);
        if (op == null) {
            return true; // unannotated handlers: internal/auth endpoints, actuator
        }

        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (actor == null) {
            log.warn("operation_unauthenticated op={} path={}", op.id(), request.getRequestURI());
            throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        }

        // 1. Actor plane must be admitted for this operation
        boolean admitted = false;
        for (Actor.ActorType allowed : op.actors()) {
            if (allowed == actor.type()
                || (actor.type() == Actor.ActorType.ACTING && allowed == Actor.ActorType.PERSON)) {
                admitted = true;
                break;
            }
        }
        if (!admitted) {
            log.warn("actor_type_rejected op={} actorType={} path={}", op.id(), actor.type(), request.getRequestURI());
            throw ApiException.forbidden("ACTOR_TYPE_REJECTED",
                "Actor plane '%s' is not admitted for operation %s".formatted(actor.type(), op.id()));
        }

        UUID tenantId = tenantFrom(request, actor);

        // 2. Permission check by plane
        switch (actor.type()) {
            case PERSON, ACTING -> {
                if (!op.permission().isBlank()) {
                    permissions.require(actor, op.permission(), tenantId);
                }
            }
            case MACHINE -> {
                if (!op.permission().isBlank()) {
                    permissions.requireClient(actor.clientId(), op.permission(), tenantId);
                }
            }
            case DELEGATED -> {
                if (!op.permission().isBlank()) {
                    permissions.require(actor, op.permission(), tenantId);
                    permissions.requireClient(actor.clientId(), op.permission(), tenantId);
                }
            }
        }

        log.debug("operation_admitted op={} risk={} permission={} tenant={} actor={} plane={}",
            op.id(), op.risk(), op.permission(), tenantId, actor.personId(), actor.type());
        request.setAttribute("fsp.operation", op.id());
        return true;
    }

    @SuppressWarnings("unchecked")
    private static UUID tenantFrom(HttpServletRequest request, Actor actor) {
        Object attrs = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (attrs instanceof Map<?, ?> map) {
            Object tenantId = ((Map<String, String>) map).get("tenantId");
            if (tenantId != null) {
                try {
                    return UUID.fromString(tenantId.toString());
                } catch (IllegalArgumentException ignored) {
                    // non-tenant or malformed path var: fall through to actor's active tenant
                }
            }
        }
        return actor.activeTenantId();
    }
}
