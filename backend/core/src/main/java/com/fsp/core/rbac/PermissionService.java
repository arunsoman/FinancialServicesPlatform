package com.fsp.core.rbac;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.RequestCorrelation;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase-0 scope rule:
 *  - role scope PLATFORM held on the platform root tenant reaches every tenant;
 *  - scope SUBTREE held on an ancestor tenant reaches all descendants;
 *  - scope TENANT reaches only the exact tenant;
 *  - scope EDGE is reserved (ignored in Phase-0).
 * Negative paths (denials, four-eyes blocks) are incident-logged at WARN (AGENTS.md §2c).
 */
@Slf4j
@Service
public class PermissionService {

    private final RbacRepository rbacRepository;
    private final RoleRepository roleRepository;
    private final TenantRepository tenantRepository;
    private final PermissionRepository permissionRepository;
    private final com.fsp.core.identity.PersonRepository personRepository;
    private final JdbcTemplate jdbc;

    public PermissionService(RbacRepository rbacRepository, RoleRepository roleRepository,
                             TenantRepository tenantRepository, PermissionRepository permissionRepository,
                             com.fsp.core.identity.PersonRepository personRepository, JdbcTemplate jdbc) {
        this.personRepository = personRepository;
        this.rbacRepository = rbacRepository;
        this.roleRepository = roleRepository;
        this.tenantRepository = tenantRepository;
        this.permissionRepository = permissionRepository;
        this.jdbc = jdbc;
    }

    /** Effective permission keys for the actor relative to a target tenant (chain-aware). */
    public Set<String> effectiveKeys(Actor actor, UUID targetTenantId) {
        requireActor(actor);
        // Acting (ACTING) is capped by the target tenant's ceiling — the settled D5 rule of
        // plans/act-as-tenant-plan.md §3.4. ActAsFilter stores the home tenant in the correlation
        // context; an ACTING actor without one did not come through ActAsFilter → holds nothing.
        if (Actor.ActorType.ACTING == actor.type()) {
            UUID home = RequestCorrelation.homeTenantId();
            if (home == null) {
                log.warn("permission_denied_acting_without_home personId={}", actor.personId());
                return Set.of();
            }
            return effectiveKeysForActing(actor, home, targetTenantId);
        }
        // MACHINE actors hold client_grants (kind MACHINE/ON_BEHALF), never person RBAC — the
        // FspOperationInterceptor routes them to requireClient; service-level re-checks must not
        // crash on actor.personId()==null by falling through to the person branch.
        if (Actor.ActorType.MACHINE == actor.type()) {
            return effectiveKeysForClient(actor.clientId(), targetTenantId);
        }
        // A plugin acting for a person who is themselves acting (session verified on the
        // on-behalf path, ActorAssertionService): the person side is capped the same way.
        if (Actor.ActorType.DELEGATED == actor.type()
            && RequestCorrelation.actSessionId() != null && RequestCorrelation.homeTenantId() != null) {
            return effectiveKeysForActing(actor, RequestCorrelation.homeTenantId(), targetTenantId);
        }
        Tenant target = tenantRepository.findById(targetTenantId)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant " + targetTenantId + " not found"));
        Set<UUID> chain = chainFor(actor, target);

        // Deactivated/removed persons hold NO permissions, even with a still-valid token
        String status = personRepository.findById(actor.personId()).map(p -> p.status).orElse(null);
        if (!"ACTIVE".equals(status)) {
            log.warn("permission_denied_inactive_person personId={} status={}", actor.personId(), status);
            return Set.of();
        }

        List<UserRole> assignments = rbacRepository.findByPersonId(actor.personId());
        if (assignments.isEmpty()) return Set.of();

        Set<String> keys = new HashSet<>();
        for (UUID roleId : assignments.stream().map(u -> u.roleId).distinct().toList()) {
            Role role = roleRepository.findById(roleId).orElse(null);
            if (role == null || !chain.contains(role.tenantId)) continue;
            if ("EDGE".equals(role.scope)) continue;
            keys.addAll(rbacRepository.permissionKeysForPerson(actor.personId()));
        }
        return keys;
    }

    private Set<UUID> chainFor(Actor actor, Tenant target) {
        Set<UUID> chain = new HashSet<>();
        for (String part : target.hierarchyPath.replaceFirst("^/", "").split("/")) {
            if (part.isBlank()) continue;
            try { chain.add(UUID.fromString(part)); } catch (IllegalArgumentException ignored) {}
        }
        // Security: the actor's OWN tenant must NOT qualify as target-ancestor reachability
        // (red-team round 1/3: injecting activeTenantId let any role-holder escape to unrelated
        // tenant trees — user.manage/role.manage effectively global). Reachability is target-tree
        // membership only: platform-scope roles at the root reach every org by construction,
        // subtree roles reach descendants, tenant roles reach that tenant only.
        return chain;
    }

    /** Effective permission keys for a machine client relative to a target tenant (chain-aware).
     * A client grant applies when the grant's owner_tenant_id is the target tenant or an
     * ancestor of it (subtree reachability, same chain math as effectiveKeys). */
    public Set<String> effectiveKeysForClient(String clientId, UUID targetTenantId) {
        Tenant target = tenantRepository.findById(targetTenantId)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant " + targetTenantId + " not found"));
        Set<UUID> chain = new HashSet<>();
        for (String part : target.hierarchyPath.replaceFirst("^/", "").split("/")) {
            if (part.isBlank()) continue;
            try { chain.add(UUID.fromString(part)); } catch (IllegalArgumentException ignored) {}
        }
        chain.add(targetTenantId);

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT owner_tenant_id, operation_or_permission FROM client_grants WHERE client_id = ?",
            clientId);

        Set<String> keys = new HashSet<>();
        for (Map<String, Object> row : rows) {
            UUID ownerTenantId = UUID.fromString(row.get("owner_tenant_id").toString());
            if (chain.contains(ownerTenantId)) {
                keys.add((String) row.get("operation_or_permission"));
            }
        }
        return keys;
    }

    public void requireClient(String clientId, String permission, UUID targetTenantId) {
        Set<String> keys = effectiveKeysForClient(clientId, targetTenantId);
        if (!keys.contains(permission)) {
            log.warn("client_permission_denied clientId={} targetTenant={} permission={}",
                clientId, targetTenantId, permission);
            throw ApiException.forbidden("CLIENT_PERMISSION_DENIED",
                "Client '%s' lacks permission '%s' at tenant %s".formatted(clientId, permission, targetTenantId));
        }
        log.debug("client_permission_granted clientId={} permission={} targetTenant={}",
            clientId, permission, targetTenantId);
    }

    /** Effective permission keys while acting as {@code targetTenantId} from {@code homeTenantId}.
     *  {@code holds} = permissions at the target ∪ permissions at the home tenant.
     *  {@code ceiling} = permissions of roles directly at the target tenant (no ancestor roles),
     *  which caps holds so acting can never escalate beyond what the target org's roles allow.
     */
    public Set<String> effectiveKeysForActing(Actor actor, UUID homeTenantId, UUID targetTenantId) {
        requireActor(actor);
        if (actor.personId() == null) {
            return Set.of();
        }
        // Holds at target + holds at home. The copies are PERSON-type so the inner effectiveKeys
        // never re-enters the acting branch (the actor itself is ACTING).
        Set<String> atTarget = effectiveKeys(
            new Actor(actor.personId(), actor.username(), targetTenantId, Actor.ActorType.PERSON, null),
            targetTenantId);
        Set<String> atHome = effectiveKeys(
            new Actor(actor.personId(), actor.username(), homeTenantId, Actor.ActorType.PERSON, null),
            homeTenantId);
        Set<String> holds = new HashSet<>(atTarget);
        holds.addAll(atHome);

        // Ceiling: roles directly at the target tenant (not ancestors)
        Set<String> ceiling = new HashSet<>();
        for (Role role : roleRepository.findByTenantId(targetTenantId)) {
            if ("EDGE".equals(role.scope)) continue;
            ceiling.addAll(rbacRepository.permissionKeysForRole(role.id));
        }

        holds.retainAll(ceiling);
        log.debug("act_as_keys personId={} home={} target={} holds={} ceiling={} result={}",
            actor.personId(), homeTenantId, targetTenantId, holds.size(), ceiling.size(), holds.size());
        return holds;
    }

    public void require(Actor actor, String permission, UUID targetTenantId) {
        Set<String> keys = effectiveKeys(actor, targetTenantId);
        if (!keys.contains(permission)) {
            log.warn("permission_denied personId={} activeTenant={} targetTenant={} permission={}",
                actor.personId(), actor.activeTenantId(), targetTenantId, permission);
            throw ApiException.forbidden("PERMISSION_DENIED",
                "Requires permission '%s' at tenant %s".formatted(permission, targetTenantId));
        }
        log.debug("permission_granted personId={} permission={} targetTenant={}",
            actor.personId(), permission, targetTenantId);
    }

    /** First satisfied permission wins (queue surfaces hold several review permissions). */
    public void requireAny(Actor actor, List<String> anyOf, UUID targetTenantId) {
        Set<String> keys = effectiveKeys(actor, targetTenantId);
        for (String permission : anyOf) {
            if (keys.contains(permission)) {
                log.debug("permission_granted personId={} permission={} targetTenant={}",
                    actor.personId(), permission, targetTenantId);
                return;
            }
        }
        log.warn("permission_denied personId={} activeTenant={} targetTenant={} anyOf={}",
            actor.personId(), actor.activeTenantId(), targetTenantId, anyOf);
        throw ApiException.forbidden("PERMISSION_DENIED",
            "Requires one of %s at tenant %s".formatted(anyOf, targetTenantId));
    }

    /** Four-eyes at person_id level: maker can never check their own request. */
    public void requireDistinctPerson(Actor checker, UUID makerId) {
        if (checker.personId().equals(makerId)) {
            log.warn("maker_cannot_approve personId={}", checker.personId());
            throw ApiException.forbidden("MAKER_CANNOT_APPROVE",
                "The person who submitted this operation cannot approve it (four-eyes at person_id)");
        }
    }

    public List<Permission> catalog() {
        List<Permission> out = new java.util.ArrayList<>();
        permissionRepository.findAll().forEach(out::add);
        out.sort((a, b) -> a.key.compareTo(b.key));
        return out;
    }

    private void requireActor(Actor actor) {
        if (actor == null) {
            throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        }
    }
}
