package com.fsp.core.common;

import java.util.UUID;

/**
 * Authenticated caller forwarded by the BFF via trusted headers.
 *
 * Plane (AGENTS.md plugin-platform-plan §4):
 *  - PERSON: a human actor (personId set, clientId null) — core's original actor model
 *  - MACHINE: a plugin's OAuth client (personId null, clientId set) — machine-to-machine calls
 *  - DELEGATED: a plugin acting on behalf of a person (personId set, clientId set) — on-behalf calls
 *  - ACTING: a signed-in person working AS a tenant below their home through an act-as session
 *    (plans/act-as-tenant-plan.md; personId set, clientId null, activeTenantId = the target).
 *    Distinct from DELEGATED on purpose: no plugin client is involved, so plugin-only gates
 *    (client grants, PLUGIN: approvals) never admit it. Permissions are person RBAC capped by
 *    the target tenant's ceiling (PermissionService); audit names the real person + session.
 *
 * A compatibility constructor keeps the 3-arg call sites (ActorContextFilter,
 * UserAdminService, tests) compiling unchanged — they map to PERSON with null clientId.
 */
public record Actor(UUID personId, String username, UUID activeTenantId, ActorType type, String clientId) {

    public enum ActorType { PERSON, MACHINE, DELEGATED, ACTING }

    /** A human at the keyboard on the person plane: signed in as themselves or acting as a tenant. */
    public boolean isHumanPlane() {
        return type == ActorType.PERSON || type == ActorType.ACTING;
    }

    public Actor(UUID personId, String username, UUID activeTenantId) {
        this(personId, username, activeTenantId, ActorType.PERSON, null);
    }
}
