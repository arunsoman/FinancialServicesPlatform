package com.fsp.core.tenant;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.config.FspProperties;
import com.fsp.core.identity.Person;
import com.fsp.core.identity.PersonProvisioning;
import com.fsp.core.identity.PersonRepository;
import com.fsp.core.rbac.PermissionService;
import com.fsp.core.rbac.Role;
import com.fsp.core.rbac.RoleTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Tenant hierarchy management. Platform root creates PSP tenants (with maker+checker personnel
 * and scoped roles); PSP admins onboard supply-chain tenants through the KYC plugin flow.
 */
@Slf4j
@Service
public class TenantService {

    /**
     * Every PSP created under the platform root is a supply-chain MANUFACTURER: the portal
     * eligibility key (frontend portal-config) and the role templates read it.
     */
    static final String PSP_SUB_TYPE = "MANUFACTURER";

    private final TenantRepository tenantRepository;
    private final PersonRepository personRepository;
    private final PersonProvisioning provisioning;
    private final PermissionService permissions;
    private final FspProperties props;

    public TenantService(TenantRepository tenantRepository, PersonRepository personRepository,
                         PersonProvisioning provisioning, PermissionService permissions,
                         FspProperties props) {
        this.tenantRepository = tenantRepository;
        this.personRepository = personRepository;
        this.provisioning = provisioning;
        this.permissions = permissions;
        this.props = props;
    }

    /** POST /v1/tenants — platform admin only (org.create + caller must sit on the PLATFORM root). */
    @Transactional
    public TenantDtos.PspCreated createPsp(Actor actor, TenantDtos.CreatePspRequest req) {
        permissions.require(actor, "org.create", actor.activeTenantId());
        Tenant parent = tenantRepository.findById(actor.activeTenantId())
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Caller tenant missing"));
        if (!"PLATFORM".equals(parent.type)) {
            log.warn("psp_create_denied actor={} type={}", actor.personId(), parent.type);
            throw ApiException.forbidden("TENANT_TYPE", "Only the platform root can create PSP tenants");
        }
        if (req.legalName() == null || req.legalName().isBlank()
            || req.adminUsername() == null || req.adminUsername().isBlank()
            || req.checkerUsername() == null || req.checkerUsername().isBlank()) {
            throw ApiException.badRequest("TENANT_FIELDS", "legalName, adminUsername, checkerUsername required");
        }

        Tenant psp = new Tenant();
        psp.id = UUID.randomUUID();
        psp.parentTenantId = parent.id;
        psp.depth = parent.depth + 1;
        psp.hierarchyPath = parent.hierarchyPath + "/" + psp.id;
        psp.type = "PSP";
        psp.subType = PSP_SUB_TYPE;
        psp.status = "ACTIVE";
        psp.verificationStatus = "VERIFIED";
        psp.legalName = req.legalName().trim();
        psp.createdBy = actor.personId();
        psp.activatedBy = actor.personId();
        tenantRepository.save(psp);

        Role adminRole = provisioning.ensureRole(psp.id, RoleTemplates.ADMIN_ROLE, "Tenant Admin", "TENANT",
            RoleTemplates.adminKeys(psp.subType));
        Role checkerRole = provisioning.ensureRole(psp.id, RoleTemplates.CHECKER_ROLE, "Tenant Checker", "TENANT",
            RoleTemplates.checkerKeys(psp.subType, false));

        String defaultPassword = props.getSecurity().getDefaultPersonPassword();
        Person admin = provisioning.createPerson(psp.id, req.adminUsername(),
            req.adminFullName() == null || req.adminFullName().isBlank()
                ? req.adminUsername() : req.adminFullName(), null, defaultPassword, false, true);
        Person checker = provisioning.createPerson(psp.id, req.checkerUsername(),
            req.checkerFullName() == null || req.checkerFullName().isBlank()
                ? req.checkerUsername() : req.checkerFullName(), null, defaultPassword, false, true);
        provisioning.assignRole(admin.id, adminRole.id);
        provisioning.assignRole(checker.id, checkerRole.id);
        // the provisioned checker IS the organization's designated checker (docs/checker-flag.md)
        provisioning.designateChecker(checker);

        log.info("psp_created tenantId={} admin={} checker={} depth={}", psp.id, admin.username, checker.username, psp.depth);
        return new TenantDtos.PspCreated(psp.id, admin.username, checker.username,
            "Temporary password: use the value of FSP_PERSON_PASSWORD (default fsp-person-2026); reset on first login in hardening slices");
    }

    /** Scoped reads: any effective permission on the target chain, otherwise hidden. */
    public TenantDtos.TenantView detail(UUID tenantId, Actor actor) {
        requireReachable(actor, tenantId);
        Tenant t = tenantRepository.findById(tenantId)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found"));
        return view(t);
    }

    private static final java.util.Set<String> CHILD_SORTS = java.util.Set.of("legalName", "status", "createdAt", "subType");

    /** Paged, sorted, searched children (AGENTS §2.7): bounded size, allow-listed sort, DB-side filter. */
    public TenantDtos.ChildPage childrenPage(UUID tenantId, Actor actor, Integer page, Integer size, String q, String sort) {
        requireReachable(actor, tenantId);
        int p = page == null || page < 0 ? 0 : page;
        int n = size == null ? 25 : Math.max(1, Math.min(size, 100));
        String[] parts = sort == null ? new String[0] : sort.split(",");
        String key = parts.length > 0 && CHILD_SORTS.contains(parts[0].trim()) ? parts[0].trim() : "legalName";
        boolean desc = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());
        var pageable = org.springframework.data.domain.PageRequest.of(p, n,
            org.springframework.data.domain.Sort.by(desc ? org.springframework.data.domain.Sort.Direction.DESC
                : org.springframework.data.domain.Sort.Direction.ASC, key).and(org.springframework.data.domain.Sort.by("id")));
        String text = q == null || q.trim().length() < 2 ? null : q.trim();
        var rows = text == null
            ? tenantRepository.findByParentTenantId(tenantId, pageable)
            : tenantRepository.findByParentTenantIdAndLegalNameContainingIgnoreCase(tenantId, text, pageable);
        return new TenantDtos.ChildPage(rows.getContent().stream().map(TenantService::view).toList(),
            p, n, rows.hasNext(), key + "," + (desc ? "desc" : "asc"), text);
    }

    public List<TenantDtos.TenantView> children(UUID tenantId, Actor actor) {
        requireReachable(actor, tenantId);
        return tenantRepository.findByParentTenantId(tenantId).stream().map(TenantService::view).toList();
    }

    private void requireReachable(Actor actor, UUID tenantId) {
        permissions.requireAny(actor, List.of(
            "je.view", "gl.account.view", "audit.view",
            "org.create", "org.activate"), tenantId);
    }

    private static TenantDtos.TenantView view(Tenant t) {
        return new TenantDtos.TenantView(t.id, t.parentTenantId, t.hierarchyPath, t.depth, t.type,
            t.subType, t.status, t.verificationStatus, t.legalName, t.createdAt, TenantLadder.childSubTypeOf(t));
    }
}
