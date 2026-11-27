package com.fsp.core.plugin.authz;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.identity.PersonProvisioning;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.Role;
import com.fsp.core.rbac.RoleTemplates;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import com.fsp.core.audit.AuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Tenant children creation for the plugin onboarding saga (§12.5 step 4).
 * Core part is atomic: tenant PENDING + applicant admin + suspended books.
 * Any failure rolls back everything (PRD §3.9).
 * Called by plugins on behalf of a person (DELEGATED actor).
 */
@Slf4j
@Service
public class TenantChildrenService {

    private final TenantRepository tenantRepository;
    private final PersonProvisioning provisioning;
    private final AuditService audit;
    private final OutboxService outbox;
    private final com.fsp.core.config.FspProperties props; // kept for wiring compatibility
    private final com.fsp.core.ledger.LedgerService ledger;

    public TenantChildrenService(TenantRepository tenantRepository, PersonProvisioning provisioning,
                                 AuditService audit, OutboxService outbox,
                                 com.fsp.core.config.FspProperties props,
                                 com.fsp.core.ledger.LedgerService ledger) {
        this.tenantRepository = tenantRepository;
        this.provisioning = provisioning;
        this.audit = audit;
        this.outbox = outbox;
        this.props = props;
        this.ledger = ledger;
    }

    /** V36: who an org-born person is and where their activation goes (primary contact). */
    public record Invitee(String fullName, String email, String phone, String primaryContact) {}

    /** The created org plus the persons whose activation must be sent once this tx commits. */
    public record ChildCreated(Tenant tenant, List<UUID> invitedPersonIds) {}

    @Transactional
    public ChildCreated createChild(UUID parentTenantId, String legalName, String subType,
                              String adminUsername, String adminFullName,
                              Invitee admin, Invitee checker, Actor actor) {
        // Actor must be DELEGATED (person acting through plugin), PERSON, or ACTING (act-as)
        if (actor.type() != Actor.ActorType.DELEGATED && !actor.isHumanPlane()) {
            throw ApiException.forbidden("ACTOR_TYPE_REJECTED",
                "Only person or delegated actors can create child tenants");
        }
        if (actor.personId() == null) {
            throw ApiException.forbidden("ACTOR_TYPE_REJECTED",
                "Maker must be a person (four-eyes at person_id)");
        }

        Tenant parent = tenantRepository.findById(parentTenantId)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Parent tenant not found"));
        if (!"ACTIVE".equals(parent.status)) {
            throw ApiException.conflict("PARENT_NOT_ACTIVE", "Parent tenant is not ACTIVE");
        }
        if (legalName == null || legalName.isBlank()) {
            throw ApiException.badRequest("TENANT_FIELDS", "legalName is required");
        }

        // the ladder (TenantLadder): an org onboards only the next level down; the default is that level
        String allowed = com.fsp.core.tenant.TenantLadder.childSubTypeOf(parent);
        if (allowed == null) {
            log.warn("tenant_child_refused_leaf parentId={} parentSubType={} maker={}",
                parent.id, parent.subType, actor.personId());
            throw ApiException.conflict("CHILD_NOT_ALLOWED",
                "%s organizations can't onboard organizations below them".formatted(
                    parent.subType == null ? "These" : parent.subType.charAt(0) + parent.subType.substring(1).toLowerCase()));
        }
        String st = subType == null || subType.isBlank() ? allowed : subType.trim().toUpperCase();
        if (!allowed.equals(st)) {
            log.warn("tenant_child_refused_rung parentId={} asked={} allowed={} maker={}",
                parent.id, st, allowed, actor.personId());
            throw ApiException.badRequest("CHILD_SUBTYPE_NOT_ALLOWED",
                "Below this organization you can only onboard a " + allowed.toLowerCase());
        }

        // V36: org persons activate through a one-time link/code on their primary contact —
        // never the shared bootstrap password. Both contacts are validated before anything persists.
        if (admin == null || checker == null) {
            throw ApiException.badRequest("CONTACT_REQUIRED",
                "Administrator and checker contacts are required (their activation goes there)");
        }
        PersonProvisioning.contact(admin.email(), admin.phone(), admin.primaryContact());
        PersonProvisioning.contact(checker.email(), checker.phone(), checker.primaryContact());

        Tenant child = new Tenant();
        child.id = UUID.randomUUID();
        child.parentTenantId = parent.id;
        child.depth = parent.depth + 1;
        child.hierarchyPath = parent.hierarchyPath + "/" + child.id;
        child.type = "PLATFORM".equals(parent.type) ? "PSP" : "TENANT";
        child.subType = st;
        child.status = "PENDING";
        child.verificationStatus = "PENDING";
        child.legalName = legalName.trim();
        child.createdBy = actor.personId();
        tenantRepository.save(child);

        // Provision applicant admin + checker roles
        // templates by sub-type: a leaf (MERCHANT) admin never onboards children (RoleTemplates)
        Role adminRole = provisioning.ensureRole(child.id, RoleTemplates.ADMIN_ROLE, "Tenant Admin", "TENANT",
            RoleTemplates.adminKeys(st));
        Role checkerRole = provisioning.ensureRole(child.id, RoleTemplates.CHECKER_ROLE, "Tenant Checker", "TENANT",
            RoleTemplates.checkerKeys(st, true));

        // credentials belong to the IdP: each person sets their own via the activation (V36)
        String adminName = admin.fullName() != null && !admin.fullName().isBlank() ? admin.fullName()
            : adminFullName != null ? adminFullName : adminUsername;
        String checkerName = checker.fullName() != null && !checker.fullName().isBlank() ? checker.fullName()
            : "Checker for " + adminUsername;
        var adminPerson = provisioning.createInvitedPerson(child.id, adminUsername, adminName,
            admin.email(), admin.phone(), admin.primaryContact(), "ACTIVE", true, false);
        var checkerPerson = provisioning.createInvitedPerson(child.id, "checker-" + adminUsername, checkerName,
            checker.email(), checker.phone(), checker.primaryContact(), "ACTIVE", true, false);
        provisioning.assignRole(adminPerson.id, adminRole.id);
        provisioning.assignRole(checkerPerson.id, checkerRole.id);
        provisioning.designateChecker(checkerPerson);

        // Born with a SUSPENDED operating book (Phase-0: NAD); verification opens it (docs §7.3).
        ledger.createBook(child.id, legalName + " Operating Book", "NAD");

        audit.record(actor.personId(), null, child.id, null,
            "TENANT_CHILD_CREATED", "TENANT", child.id.toString(),
            null, audit.hashState("status=PENDING;parent=" + parentTenantId), false);
        outbox.record("Tenant", child.id, "Tenant.ChildCreated",
            "{\"tenantId\":\"%s\",\"parentId\":\"%s\",\"legalName\":\"%s\",\"subType\":\"%s\",\"maker\":\"%s\"}"
                .formatted(child.id, parentTenantId, legalName, st, actor.personId()));

        log.info("tenant_child_created tenantId={} parentId={} maker={} subType={}",
            child.id, parentTenantId, actor.personId(), st);
        return new ChildCreated(child, List.of(adminPerson.id, checkerPerson.id));
    }
}
