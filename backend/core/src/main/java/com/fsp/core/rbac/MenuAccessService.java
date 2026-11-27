package com.fsp.core.rbac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.RequestCorrelation;
import com.fsp.core.identity.PersonRepository;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import com.fsp.core.ui.UiLayout;
import com.fsp.core.ui.UiLayoutRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Menu-cell access (the Permissions Matrix): which PUBLISHED submenus a person may see (ACTIVE)
 * and which actions they hold on them, per portal.
 *
 * <ul>
 *   <li><b>Full menu</b>: a platform-scope role, or an organization's anchor role
 *       ({@code TENANT_ADMIN} / {@code TENANT_CHECKER}) reachable for the tenant, holds every cell
 *       of every published menu implicitly — so the people who build roles can never lock
 *       themselves out of a newly published submenu.</li>
 *   <li>Otherwise: the union of the cells granted to the person's reachable roles for that portal
 *       ({@code role_menu_grants}). Reachability = the same tenant-chain rule as permission keys.</li>
 *   <li>Machines hold no menu cells; deactivated people hold none.</li>
 * </ul>
 * The grid is validated against the portal's PUBLISHED layout ({@code ui_layouts}), never a coded menu.
 */
@Slf4j
@Service
public class MenuAccessService {

    /** Signed-delivery key of the portal each organization's people sign in to (portal-config.ts). */
    private static final Map<String, String> PORTAL_BY_SUB_TYPE = Map.of(
        "MANUFACTURER", "manufacturer", "DISTRIBUTOR", "distributor",
        "WHOLESALER", "wholesaler", "MERCHANT", "merchant");
    private static final Map<String, String> PORTAL_BY_TYPE = Map.of("PLATFORM", "admin", "PSP", "manufacturer");
    public static final Set<String> PORTALS = Set.of("admin", "manufacturer", "distributor", "wholesaler", "merchant");
    private static final Set<String> ANCHOR_ROLES = Set.of(RoleTemplates.ADMIN_ROLE, RoleTemplates.CHECKER_ROLE);

    public record MenuAccess(String portal, boolean full, Set<String> cells) {
        public boolean holds(String wire) {
            return full || cells.contains(wire);
        }
    }

    private final TenantRepository tenants;
    private final RoleRepository roles;
    private final RbacRepository rbac;
    private final PersonRepository persons;
    private final RoleMenuGrantRepository grants;
    private final UiLayoutRepository layouts;
    private final ObjectMapper mapper;

    public MenuAccessService(TenantRepository tenants, RoleRepository roles, RbacRepository rbac, PersonRepository persons,
                             RoleMenuGrantRepository grants, UiLayoutRepository layouts, ObjectMapper mapper) {
        this.tenants = tenants;
        this.roles = roles;
        this.rbac = rbac;
        this.persons = persons;
        this.grants = grants;
        this.layouts = layouts;
        this.mapper = mapper;
    }

    /** The portal an organization's people sign in to; null when none admits it. */
    @Transactional(readOnly = true)
    public String portalOf(UUID tenantId) {
        Tenant t = tenants.findById(tenantId)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Organization not found"));
        return portalOf(t);
    }

    static String portalOf(Tenant t) {
        if ("PLATFORM".equals(t.type)) return "admin";
        if (t.subType != null) return PORTAL_BY_SUB_TYPE.get(t.subType.toUpperCase(Locale.ROOT));
        return PORTAL_BY_TYPE.get(t.type);
    }

    /** The submenus ({@code menu/screen}) of the portal's PUBLISHED layout; empty when none is published. */
    @Transactional(readOnly = true)
    public Set<String> publishedScreens(String portal) {
        UiLayout live = layouts.findFirstByPortalAndStatus(portal, UiLayout.Status.PUBLISHED).orElse(null);
        if (live == null) return Set.of();
        Set<String> out = new TreeSet<>();
        try {
            JsonNode root = mapper.readTree(live.layoutJson);
            for (JsonNode m : root.path("menus")) {
                for (JsonNode s : m.path("screens")) out.add(m.path("id").asText() + "/" + s.path("id").asText());
            }
        } catch (Exception e) {
            log.error("menu_access_layout_unreadable portal={} layoutId={}", portal, live.id, e);
            throw ApiException.conflict("UI_LAYOUT_INVALID", "The published " + portal + " layout is unreadable");
        }
        return out;
    }

    /** Every proposed cell must name a submenu of the portal's PUBLISHED layout. */
    @Transactional(readOnly = true)
    public void requirePublished(String portal, Collection<String> cells) {
        if (cells.isEmpty()) return;
        Set<String> screens = publishedScreens(portal);
        if (screens.isEmpty()) {
            throw ApiException.conflict("MENU_NOT_PUBLISHED", "The " + portal + " portal has no published menu yet");
        }
        List<String> unknown = cells.stream().filter(c -> !screens.contains(MenuCell.parse(c).screen())).sorted().toList();
        if (!unknown.isEmpty()) {
            log.warn("menu_cell_unknown portal={} cells={}", portal, unknown);
            throw ApiException.conflict("MENU_CELL_UNKNOWN",
                "Not on the " + portal + " portal's published menu (it may have changed): " + unknown);
        }
    }

    /** What the actor holds on {@code portal}'s menu, for the organization {@code tenantId}. */
    @Transactional(readOnly = true)
    public MenuAccess forActor(Actor actor, UUID tenantId, String portal) {
        if (actor == null) throw ApiException.unauthorized("ACTOR_MISSING", "No authenticated actor");
        if (actor.type() == Actor.ActorType.MACHINE || actor.personId() == null) return new MenuAccess(portal, false, Set.of());
        String status = persons.findById(actor.personId()).map(p -> p.status).orElse(null);
        if (!"ACTIVE".equals(status)) {
            log.warn("menu_access_inactive_person personId={} status={}", actor.personId(), status);
            return new MenuAccess(portal, false, Set.of());
        }
        Set<UUID> chain = chain(tenantId);
        // acting as another organization: what the person holds at home counts too (keys are
        // capped by the target's ceiling in PermissionService; cells only steer navigation)
        UUID home = actor.type() == Actor.ActorType.ACTING ? RequestCorrelation.homeTenantId() : null;
        if (home != null) chain.addAll(chain(home));

        List<UUID> assigned = rbac.findByPersonId(actor.personId()).stream().map(u -> u.roleId).distinct().toList();
        Set<UUID> reachable = new HashSet<>();
        boolean full = false;
        for (Role r : roles.findAllById(assigned)) {
            if (!chain.contains(r.tenantId) || "EDGE".equals(r.scope)) continue;
            reachable.add(r.id);
            if ("PLATFORM".equals(r.scope) || ANCHOR_ROLES.contains(r.key)) full = true;
        }
        if (full) return new MenuAccess(portal, true, Set.of());
        return new MenuAccess(portal, false, grants.cellsForRoles(reachable, portal));
    }

    private Set<UUID> chain(UUID tenantId) {
        Tenant t = tenants.findById(tenantId)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Organization not found"));
        Set<UUID> out = new HashSet<>();
        for (String part : t.hierarchyPath.replaceFirst("^/", "").split("/")) {
            if (part.isBlank()) continue;
            try { out.add(UUID.fromString(part)); } catch (IllegalArgumentException ignored) { /* malformed segment */ }
        }
        return out;
    }
}
