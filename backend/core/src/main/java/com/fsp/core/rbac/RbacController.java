package com.fsp.core.rbac;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.FspOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class RbacController {

    private final PermissionService permissionService;
    private final MenuAccessService menuAccess;
    private final com.fsp.core.identity.PersonRepository persons;

    public RbacController(PermissionService permissionService, MenuAccessService menuAccess,
                          com.fsp.core.identity.PersonRepository persons) {
        this.permissionService = permissionService;
        this.menuAccess = menuAccess;
        this.persons = persons;
    }

    @FspOperation(id = "rbac.permission.catalog", component = "rbac", title = "List permission definitions")
    @GetMapping("/v1/permissions")
    public List<Map<String, Object>> catalog() {
        return permissionService.catalog().stream()
            .map(p -> Map.<String, Object>of(
                "key", p.key, "title", p.title, "risk", p.risk, "component", p.component))
            .toList();
    }

    /** Also answers a plugin acting for the person (DELEGATED): it learns only that person's own
     *  keys and checker flag — how the KYC plugin shows decision controls without reading core tables. */
    @FspOperation(id = "rbac.permission.me", component = "rbac", title = "My effective permissions",
        actors = {Actor.ActorType.PERSON, Actor.ActorType.DELEGATED})
    @GetMapping("/v1/me/permissions")
    public Map<String, Object> myPermissions(jakarta.servlet.http.HttpServletRequest request) {
        Actor actor = actor(request);
        var keys = permissionService.effectiveKeys(actor, actor.activeTenantId());
        boolean checker = actor.personId() != null
            && persons.findById(actor.personId()).map(p -> p.isChecker).orElse(false);
        return Map.of(
            "tenantId", actor.activeTenantId().toString(),
            "permissions", keys.stream().sorted().toList(),
            "checker", checker);
    }

    /**
     * The submenus of {@code portal}'s published menu the caller may see (ACTIVE) and the actions
     * they hold there — the Permissions Matrix cells of their roles ({@code full} = every cell).
     * The portal's sidebar draws only what this allows; core still checks every API call.
     */
    @FspOperation(id = "rbac.menu.me", component = "rbac", title = "My menu access on a portal")
    @GetMapping("/v1/me/menu-access")
    public Map<String, Object> myMenuAccess(jakarta.servlet.http.HttpServletRequest request,
                                            @org.springframework.web.bind.annotation.RequestParam("portal") String portal) {
        Actor actor = actor(request);
        if (portal == null || !MenuAccessService.PORTALS.contains(portal)) {
            throw ApiException.badRequest("PORTAL_UNKNOWN", "Unknown portal");
        }
        MenuAccessService.MenuAccess a = menuAccess.forActor(actor, actor.activeTenantId(), portal);
        return Map.of("portal", a.portal(), "full", a.full(), "cells", List.copyOf(a.cells()));
    }

    private Actor actor(jakarta.servlet.http.HttpServletRequest request) {
        Actor actor = (Actor) request.getAttribute("fsp.actor");
        if (actor == null) {
            throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        }
        return actor;
    }
}
