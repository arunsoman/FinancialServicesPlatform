package com.fsp.core.orgunit;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.rbac.PermissionService;
import com.fsp.core.tenant.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * An organization's branches and departments: paginated, sorted and searched in the database
 * (AGENTS §2f), read by whoever may read or manage the organization's roles (the Add Role form
 * picks from them). Also the guard {@code RoleAdminService} uses to anchor a role to a unit.
 */
@Slf4j
@Service
public class OrgUnitService {

    public enum Kind { BRANCH, DEPARTMENT }

    static final int MAX_PAGE_SIZE = 100;
    private static final Map<String, String> SORTABLE = Map.of("name", "name", "code", "code", "createdAt", "createdAt");

    private final BranchRepository branches;
    private final DepartmentRepository departments;
    private final TenantRepository tenants;
    private final PermissionService permissions;

    public OrgUnitService(BranchRepository branches, DepartmentRepository departments, TenantRepository tenants,
                          PermissionService permissions) {
        this.branches = branches;
        this.departments = departments;
        this.tenants = tenants;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public OrgUnitDtos.OrgUnitPage list(Kind kind, UUID tenantId, int page, int size, String sort, String q,
                                        String status, Actor actor) {
        permissions.requireAny(actor, List.of("role.view", "role.manage"), tenantId);
        if (!tenants.existsById(tenantId)) throw ApiException.notFound("TENANT_NOT_FOUND", "Organization not found");
        String st = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
        if (st != null && !OrgUnit.ACTIVE.equals(st) && !OrgUnit.ARCHIVED.equals(st)) {
            throw ApiException.badRequest("ORG_UNIT_STATUS_INVALID", "status must be ACTIVE or ARCHIVED");
        }
        String needle = q == null ? "" : q.strip().toLowerCase(Locale.ROOT); // "" matches every row
        PageRequest pr = pageRequest(page, size, sort);
        Page<? extends OrgUnit> result = kind == Kind.BRANCH
            ? branches.search(tenantId, st, needle, pr)
            : departments.search(tenantId, st, needle, pr);
        List<OrgUnitDtos.OrgUnitView> items = result.getContent().stream()
            .map(u -> new OrgUnitDtos.OrgUnitView(u.id, u.code, u.name, u.status)).toList();
        Sort.Order order = pr.getSort().iterator().next();
        return new OrgUnitDtos.OrgUnitPage(items, result.getNumber(), result.getSize(), result.getTotalElements(),
            result.getTotalPages(), result.hasNext(), order.getProperty() + "," + order.getDirection().name().toLowerCase(Locale.ROOT));
    }

    /** True when the organization has at least one ACTIVE unit of this kind (then a role must name one). */
    @Transactional(readOnly = true)
    public boolean hasActive(Kind kind, UUID tenantId) {
        return kind == Kind.BRANCH
            ? branches.existsByTenantIdAndStatus(tenantId, OrgUnit.ACTIVE)
            : departments.existsByTenantIdAndStatus(tenantId, OrgUnit.ACTIVE);
    }

    /**
     * A role's unit: must belong to the role's organization and be ACTIVE. Another organization's
     * unit is indistinguishable from a missing one (no cross-tenant probing).
     */
    @Transactional(readOnly = true)
    public OrgUnit requireActive(Kind kind, UUID tenantId, UUID id) {
        OrgUnit u = kind == Kind.BRANCH ? branches.findById(id).orElse(null) : departments.findById(id).orElse(null);
        String label = kind == Kind.BRANCH ? "Branch" : "Department";
        if (u == null || !tenantId.equals(u.tenantId)) {
            log.warn("org_unit_not_in_tenant kind={} tenant={} unit={}", kind, tenantId, id);
            throw ApiException.badRequest(kind + "_UNKNOWN", label + " not found in this organization");
        }
        if (!OrgUnit.ACTIVE.equals(u.status)) {
            log.warn("org_unit_archived kind={} tenant={} unit={}", kind, tenantId, id);
            throw ApiException.badRequest(kind + "_ARCHIVED", label + " " + u.name + " is archived");
        }
        return u;
    }

    /** Display name of a unit (null-safe; a missing unit shows as null). */
    @Transactional(readOnly = true)
    public String nameOf(Kind kind, UUID id) {
        if (id == null) return null;
        OrgUnit u = kind == Kind.BRANCH ? branches.findById(id).orElse(null) : departments.findById(id).orElse(null);
        return u == null ? null : u.name;
    }

    static PageRequest pageRequest(int page, int size, String sort) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("PAGE_INVALID", "page must be ≥ 0 and size 1–" + MAX_PAGE_SIZE);
        }
        String field = "name";
        Sort.Direction dir = Sort.Direction.ASC;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",", 2);
            String mapped = SORTABLE.get(parts[0].strip());
            if (mapped == null) throw ApiException.badRequest("SORT_INVALID", "sort field must be one of " + SORTABLE.keySet());
            field = mapped;
            if (parts.length == 2) {
                String d = parts[1].strip();
                if ("asc".equalsIgnoreCase(d)) dir = Sort.Direction.ASC;
                else if ("desc".equalsIgnoreCase(d)) dir = Sort.Direction.DESC;
                else throw ApiException.badRequest("SORT_INVALID", "sort direction must be asc or desc");
            }
        }
        return PageRequest.of(page, size, Sort.by(dir, field).and(Sort.by(Sort.Direction.ASC, "id")));
    }
}
