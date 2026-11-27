package com.fsp.core.orgunit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.approval.ApprovalRequestRepository;
import com.fsp.core.approval.ApprovalService;
import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.identity.Person;
import com.fsp.core.identity.PersonRepository;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.PermissionService;
import com.fsp.core.tenant.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Branch maintenance (Role Management → Branch; widget org.branches). Read with role.view or
 * role.manage; every write is four-eyes with role.manage: the maker FILES a creation (a PENDING
 * row), a rename or a deletion (nothing changes yet), core opens a BRANCH_* approval, and only a
 * DIFFERENT person with the checker flag applies it ({@link BranchApprovalHandlers}, atomic with
 * the approval row). Transitions are conditional UPDATEs on (version, status) — a stale or
 * concurrent change fails with 409, never overwrites (AGENTS §2d).
 */
@Slf4j
@Service
public class BranchService {

    static final String MANAGE = "role.manage";
    static final String VIEW = "role.view";
    static final int NAME_MAX = 80;
    /** mirrors the widget's zod rule (model.ts branchFormSchema) */
    private static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} .,'&()/-]*$");
    private static final Set<String> VIEWS = Set.of("ALL", "CURRENT", "AWAITING",
        Branch.PENDING, OrgUnit.ACTIVE, Branch.REJECTED, OrgUnit.ARCHIVED);
    static final Set<String> BRANCH_OPS = Set.of(ApprovalRequest.OpType.BRANCH_CREATE,
        ApprovalRequest.OpType.BRANCH_UPDATE, ApprovalRequest.OpType.BRANCH_DELETE);
    /** makerName of system-seeded rows (no person filed them) */
    static final String SYSTEM = "System";

    private final BranchRepository branches;
    private final TenantRepository tenants;
    private final PermissionService permissions;
    private final ApprovalService approvals;
    private final ApprovalRequestRepository approvalRows;
    private final PersonRepository persons;
    private final AuditService audit;
    private final OutboxService outbox;
    private final ObjectMapper mapper;

    public BranchService(BranchRepository branches, TenantRepository tenants, PermissionService permissions,
                         ApprovalService approvals, ApprovalRequestRepository approvalRows, PersonRepository persons,
                         AuditService audit, OutboxService outbox, ObjectMapper mapper) {
        this.branches = branches;
        this.tenants = tenants;
        this.permissions = permissions;
        this.approvals = approvals;
        this.approvalRows = approvalRows;
        this.persons = persons;
        this.audit = audit;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ reads

    /**
     * One page of the organization's branches. {@code status}: CURRENT (PENDING + ACTIVE),
     * AWAITING (a change waits for a checker), a single status, or absent = all. Search, filter,
     * sort and paging run in SQL (AGENTS §2f).
     */
    @Transactional(readOnly = true)
    public BranchDtos.BranchPage list(UUID tenantId, int page, int size, String sort, String q, String status,
                                      Actor actor) {
        requireRead(actor, tenantId);
        String view = status == null || status.isBlank() ? "ALL" : status.strip().toUpperCase(Locale.ROOT);
        if (!VIEWS.contains(view)) {
            throw ApiException.badRequest("BRANCH_STATUS_INVALID", "status must be one of " + VIEWS);
        }
        String needle = q == null ? "" : q.strip().toLowerCase(Locale.ROOT); // "" matches every row
        PageRequest pr = OrgUnitService.pageRequest(page, size, sort);
        Page<Branch> result = branches.page(tenantId, view, needle, pr);
        List<BranchDtos.BranchView> items = views(result.getContent(), caller(actor, tenantId));
        Sort.Order order = pr.getSort().iterator().next();
        return new BranchDtos.BranchPage(items, result.getNumber(), result.getSize(), result.getTotalElements(),
            result.getTotalPages(), result.hasNext(),
            order.getProperty() + "," + order.getDirection().name().toLowerCase(Locale.ROOT));
    }

    @Transactional(readOnly = true)
    public BranchDtos.BranchView get(UUID tenantId, UUID branchId, Actor actor) {
        requireRead(actor, tenantId);
        return views(List.of(load(tenantId, branchId)), caller(actor, tenantId)).getFirst();
    }

    // ---------------------------------------------------------- writes (maker)

    /** Files a new branch: a PENDING row (it holds its name) + a BRANCH_CREATE approval. */
    @Transactional
    public BranchDtos.BranchChange requestCreate(UUID tenantId, BranchDtos.CreateBranch in, Actor actor) {
        requireManage(actor, tenantId);
        requirePersonMaker(actor);
        if (in == null) throw ApiException.badRequest("BODY_INVALID", "Request body required");
        String name = requireName(in.name());
        requireNameFree(tenantId, name, null);

        Instant now = Instant.now();
        Branch b = new Branch();
        b.id = UUID.randomUUID();
        b.tenantId = tenantId;
        b.code = "BR-%04d".formatted(branches.nextCodeNumber());
        b.name = name;
        b.status = Branch.PENDING;
        b.makerId = actor.personId();
        b.createdAt = now;
        b.updatedAt = now;
        try {
            b = branches.saveAndFlush(b);
        } catch (DataIntegrityViolationException e) { // a concurrent filing took the name first
            log.warn("branch_name_taken_on_insert tenant={} name={}", tenantId, name);
            throw nameTaken(name);
        }
        BranchDtos.BranchProposal p = new BranchDtos.BranchProposal(b.version, name);
        ApprovalRequest row = approvals.open(tenantId, ApprovalRequest.OpType.BRANCH_CREATE, b.id, actor,
            "branch-create:" + b.code + ":" + name, json(p));
        record(actor.personId(), null, tenantId, "BRANCH_CREATE_REQUESTED", b.id, null, snapshot(b.code, name, b.status));
        log.info("branch_create_requested tenant={} branch={} code={} approvalId={} by={}",
            tenantId, b.id, b.code, row.id, actor.personId());
        return new BranchDtos.BranchChange(row.id, row.operationType, b.id, row.status);
    }

    /** Files a rename of an ACTIVE branch — the branch keeps its name until a checker approves. */
    @Transactional
    public BranchDtos.BranchChange requestUpdate(UUID tenantId, UUID branchId, BranchDtos.UpdateBranch in, Actor actor) {
        requireManage(actor, tenantId);
        requirePersonMaker(actor);
        if (in == null) throw ApiException.badRequest("BODY_INVALID", "Request body required");
        Branch b = load(tenantId, branchId);
        requireChangeable(b, in.expectedVersion());
        String name = requireName(in.name());
        if (name.equals(b.name)) {
            throw ApiException.badRequest("BRANCH_NAME_UNCHANGED", "The new name is the same as the current one");
        }
        requireNameFree(tenantId, name, b.id);
        long base = pin(b);

        BranchDtos.BranchProposal p = new BranchDtos.BranchProposal(base, name);
        ApprovalRequest row = approvals.open(tenantId, ApprovalRequest.OpType.BRANCH_UPDATE, b.id, actor,
            "branch-update:" + b.code + ":v" + base + ":" + name, json(p));
        record(actor.personId(), null, tenantId, "BRANCH_UPDATE_REQUESTED", b.id,
            snapshot(b.code, b.name, b.status), snapshot(b.code, name, b.status));
        log.info("branch_update_requested tenant={} branch={} approvalId={} by={}", tenantId, b.id, row.id, actor.personId());
        return new BranchDtos.BranchChange(row.id, row.operationType, b.id, row.status);
    }

    /** Files the deletion of an ACTIVE branch no role uses — it stays active until approved. */
    @Transactional
    public BranchDtos.BranchChange requestDelete(UUID tenantId, UUID branchId, Long expectedVersion, Actor actor) {
        requireManage(actor, tenantId);
        requirePersonMaker(actor);
        Branch b = load(tenantId, branchId);
        requireChangeable(b, expectedVersion);
        requireUnused(b);
        long base = pin(b);

        BranchDtos.BranchProposal p = new BranchDtos.BranchProposal(base, b.name);
        ApprovalRequest row = approvals.open(tenantId, ApprovalRequest.OpType.BRANCH_DELETE, b.id, actor,
            "branch-delete:" + b.code + ":v" + base, json(p));
        record(actor.personId(), null, tenantId, "BRANCH_DELETE_REQUESTED", b.id, snapshot(b.code, b.name, b.status), null);
        log.info("branch_delete_requested tenant={} branch={} approvalId={} by={}", tenantId, b.id, row.id, actor.personId());
        return new BranchDtos.BranchChange(row.id, row.operationType, b.id, row.status);
    }

    // ------------------------------------------------- apply (checker, atomic with the approval row)

    /** BRANCH_CREATE decided: PENDING → ACTIVE (approved) or REJECTED (kept as evidence). */
    @Transactional
    public void decideCreate(ApprovalRequest approval, boolean approved, Actor checker, String reason) {
        BranchDtos.BranchProposal p = proposal(approval);
        String to = approved ? OrgUnit.ACTIVE : Branch.REJECTED;
        int moved = branches.decideCreation(approval.targetResourceId, base(p), to, checker.personId(),
            approved ? null : clip(reason), Instant.now());
        if (moved != 1) throw stale(approval);
        record(approval.makerId, checker.personId(), approval.tenantId, approved ? "BRANCH_CREATED" : "BRANCH_REJECTED",
            approval.targetResourceId, snapshot(null, p.name(), Branch.PENDING), snapshot(null, p.name(), to));
        log.info("branch_create_decided tenant={} branch={} status={} approvalId={} checker={}",
            approval.tenantId, approval.targetResourceId, to, approval.id, checker.personId());
    }

    /** BRANCH_UPDATE approved: the version-pinned rename (the live-name index guards a race). */
    @Transactional
    public void applyUpdate(ApprovalRequest approval, Actor checker) {
        BranchDtos.BranchProposal p = proposal(approval);
        int moved;
        try {
            moved = branches.rename(approval.targetResourceId, base(p), p.name(), Instant.now());
        } catch (DataIntegrityViolationException e) {
            log.warn("branch_name_taken_on_apply approvalId={} name={}", approval.id, p.name());
            throw nameTaken(p.name());
        }
        if (moved != 1) throw stale(approval);
        record(approval.makerId, checker.personId(), approval.tenantId, "BRANCH_UPDATED", approval.targetResourceId,
            null, snapshot(null, p.name(), OrgUnit.ACTIVE));
        log.info("branch_update_applied tenant={} branch={} approvalId={} checker={}",
            approval.tenantId, approval.targetResourceId, approval.id, checker.personId());
    }

    /** BRANCH_DELETE approved: archive — only while still unused by roles (re-checked). */
    @Transactional
    public void applyDelete(ApprovalRequest approval, Actor checker) {
        BranchDtos.BranchProposal p = proposal(approval);
        Branch b = branches.findById(approval.targetResourceId).orElseThrow(() -> stale(approval));
        requireUnused(b);
        int moved = branches.archive(b.id, base(p), Instant.now());
        if (moved != 1) throw stale(approval);
        record(approval.makerId, checker.personId(), approval.tenantId, "BRANCH_DELETED", b.id,
            snapshot(b.code, b.name, OrgUnit.ACTIVE), snapshot(b.code, b.name, OrgUnit.ARCHIVED));
        log.info("branch_delete_applied tenant={} branch={} approvalId={} checker={}",
            approval.tenantId, b.id, approval.id, checker.personId());
    }

    /** A rejected rename / deletion changes nothing but the version (the pending change is gone). */
    @Transactional
    public void rejectedChange(ApprovalRequest approval, Actor checker) {
        BranchDtos.BranchProposal p = proposal(approval);
        if (branches.bump(approval.targetResourceId, base(p), Instant.now()) != 1) throw stale(approval);
        record(approval.makerId, checker.personId(), approval.tenantId, approval.operationType + "_REJECTED",
            approval.targetResourceId, null, null);
        log.info("branch_change_rejected tenant={} type={} branch={} approvalId={} checker={}",
            approval.tenantId, approval.operationType, approval.targetResourceId, approval.id, checker.personId());
    }

    // ------------------------------------------------------------------ guards

    private void requireRead(Actor actor, UUID tenantId) {
        permissions.requireAny(actor, List.of(VIEW, MANAGE), tenantId);
        requireTenant(tenantId);
    }

    private void requireManage(Actor actor, UUID tenantId) {
        permissions.require(actor, MANAGE, tenantId);
        requireTenant(tenantId);
    }

    private void requireTenant(UUID tenantId) {
        if (!tenants.existsById(tenantId)) throw ApiException.notFound("TENANT_NOT_FOUND", "Organization not found");
    }

    /** Four-eyes is person-anchored: a machine actor never files a branch change. */
    private static void requirePersonMaker(Actor actor) {
        if (actor.personId() == null || actor.type() == Actor.ActorType.MACHINE) {
            throw ApiException.forbidden("MACHINE_MAKER", "Four-eyes is person-anchored; a machine actor cannot file a branch change");
        }
    }

    /** Another organization's branch is indistinguishable from a missing one (no cross-tenant probing). */
    private Branch load(UUID tenantId, UUID branchId) {
        Branch b = branchId == null ? null : branches.findById(branchId).orElse(null);
        if (b == null || !tenantId.equals(b.tenantId)) {
            if (b != null) log.warn("branch_not_in_tenant tenant={} branch={}", tenantId, branchId);
            throw ApiException.notFound("BRANCH_NOT_FOUND", "Branch not found in this organization");
        }
        return b;
    }

    private void requireChangeable(Branch b, Long expectedVersion) {
        if (expectedVersion == null) throw ApiException.badRequest("VERSION_REQUIRED", "expectedVersion is required for this change");
        if (Branch.PENDING.equals(b.status)) {
            throw ApiException.conflict("BRANCH_STATE", "This branch is waiting for its first approval");
        }
        if (!OrgUnit.ACTIVE.equals(b.status)) throw ApiException.conflict("BRANCH_STATE", "Only active branches can be changed");
        if (b.version != expectedVersion) {
            log.warn("branch_change_stale branch={} expected={} now={}", b.id, expectedVersion, b.version);
            throw ApiException.conflict("STALE_WRITE", "Someone changed this branch since you opened it. Reload to see the latest.");
        }
        if (!approvalRows.findByTargetResourceIdAndStatus(b.id, ApprovalRequest.Status.PENDING_CHECKER).stream()
                .filter(a -> BRANCH_OPS.contains(a.operationType)).toList().isEmpty()) {
            throw ApiException.conflict("BRANCH_CHANGE_PENDING", "This branch already has a change awaiting approval");
        }
    }

    /**
     * Pins the version the proposal is made against. The conditional UPDATE row-locks the branch:
     * a concurrent filer waits, then fails its version check — one pending change per branch.
     */
    private long pin(Branch b) {
        if (branches.touchActive(b.id, b.version, Instant.now()) != 1) {
            log.warn("branch_change_race branch={} version={}", b.id, b.version);
            throw ApiException.conflict("STALE_WRITE", "Someone changed this branch since you opened it. Reload to see the latest.");
        }
        return b.version + 1;
    }

    private void requireUnused(Branch b) {
        long n = branches.countRoles(b.id);
        if (n > 0) {
            log.warn("branch_in_use branch={} roles={}", b.id, n);
            throw ApiException.conflict("BRANCH_IN_USE", "%d role%s assigned to this branch — move them to another branch first"
                .formatted(n, n == 1 ? " is" : "s are"));
        }
    }

    static String requireName(String raw) {
        String v = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        if (v.length() < 2 || v.length() > NAME_MAX || !NAME.matcher(v).matches()) {
            throw ApiException.badRequest("BRANCH_NAME_INVALID",
                "A branch name is 2–%d characters: letters, digits, spaces and . , ' & ( ) / -".formatted(NAME_MAX));
        }
        return v;
    }

    /** Live names and names already proposed by a pending rename are taken (case-insensitive). */
    private void requireNameFree(UUID tenantId, String name, UUID exceptId) {
        String lower = name.toLowerCase(Locale.ROOT);
        boolean live = branches.liveIdsByName(tenantId, lower).stream().anyMatch(id -> !id.equals(exceptId));
        boolean proposed = !live && approvalRows.findByTenantIdAndOperationTypeAndStatusInOrderByRequestedAtDesc(
                tenantId, ApprovalRequest.OpType.BRANCH_UPDATE, List.of(ApprovalRequest.Status.PENDING_CHECKER)).stream()
            .filter(a -> !Objects.equals(a.targetResourceId, exceptId))
            .anyMatch(a -> lower.equals(proposal(a).name().toLowerCase(Locale.ROOT)));
        if (live || proposed) throw nameTaken(name);
    }

    private static ApiException nameTaken(String name) {
        return ApiException.conflict("BRANCH_NAME_TAKEN", "A branch named “%s” already exists".formatted(name));
    }

    private static ApiException stale(ApprovalRequest approval) {
        log.warn("branch_change_stale_on_apply approvalId={} type={} branch={}",
            approval.id, approval.operationType, approval.targetResourceId);
        return ApiException.conflict("STALE_WRITE", "The branch changed after this request was made; reject it and request again");
    }

    // ------------------------------------------------------------------ views

    /** What the caller may decide (mirrors ApprovalService's queue verdict; core re-checks on resolve). */
    private record Caller(UUID personId, boolean manages, boolean checker) {}

    private Caller caller(Actor actor, UUID tenantId) {
        boolean manages = permissions.effectiveKeys(actor, tenantId).contains(MANAGE);
        boolean flagged = actor.personId() != null
            && persons.findById(actor.personId()).map(p -> p.isChecker).orElse(false);
        return new Caller(actor.personId(), manages, flagged);
    }

    /** One page → views: one query for its live approvals, one for the people named (no N+1). */
    private List<BranchDtos.BranchView> views(List<Branch> page, Caller caller) {
        if (page.isEmpty()) return List.of();
        Map<UUID, ApprovalRequest> pending = new HashMap<>();
        for (ApprovalRequest a : approvalRows.findByTargetResourceIdInAndStatus(
                page.stream().map(b -> b.id).toList(), ApprovalRequest.Status.PENDING_CHECKER)) {
            if (BRANCH_OPS.contains(a.operationType)) pending.putIfAbsent(a.targetResourceId, a);
        }
        Set<UUID> people = new HashSet<>();
        for (Branch b : page) {
            if (b.makerId != null) people.add(b.makerId);
            if (b.checkerId != null) people.add(b.checkerId);
        }
        pending.values().forEach(a -> people.add(a.makerId));
        Map<UUID, String> names = new HashMap<>();
        for (Person p : persons.findAllById(people)) names.put(p.id, displayName(p));

        List<BranchDtos.BranchView> out = new ArrayList<>(page.size());
        for (Branch b : page) {
            ApprovalRequest a = pending.get(b.id);
            BranchDtos.PendingChange change = a == null ? null : pendingView(a, caller, names);
            out.add(new BranchDtos.BranchView(b.id, b.code, b.name, b.status, b.version,
                b.makerId, b.makerId == null ? SYSTEM : names.getOrDefault(b.makerId, SYSTEM), b.createdAt,
                b.checkerId, b.checkerId == null ? null : names.get(b.checkerId), b.decidedAt, b.decisionReason,
                b.updatedAt, change));
        }
        return out;
    }

    private BranchDtos.PendingChange pendingView(ApprovalRequest a, Caller caller, Map<UUID, String> names) {
        String blocked;
        if (caller.personId() != null && caller.personId().equals(a.makerId)) blocked = "OWN_REQUEST";
        else if (!caller.manages()) blocked = "NO_PERMISSION";
        else if (!caller.checker()) blocked = "NOT_CHECKER";
        else blocked = null;
        String proposedName = ApprovalRequest.OpType.BRANCH_DELETE.equals(a.operationType) ? null : proposal(a).name();
        return new BranchDtos.PendingChange(a.id, a.operationType, proposedName, a.makerId, names.get(a.makerId),
            a.requestedAt, blocked == null, blocked);
    }

    private static String displayName(Person p) {
        return p.fullName != null && !p.fullName.isBlank() ? p.fullName : p.username;
    }

    // ---------------------------------------------------------------- helpers

    private static long base(BranchDtos.BranchProposal p) {
        if (p.baseVersion() == null) throw ApiException.conflict("PAYLOAD_INVALID", "The branch change payload has no version");
        return p.baseVersion();
    }

    private static String clip(String reason) {
        if (reason == null || reason.isBlank()) return null;
        String r = reason.strip();
        return r.length() > 500 ? r.substring(0, 500) : r;
    }

    private BranchDtos.BranchProposal proposal(ApprovalRequest a) {
        try {
            return mapper.readValue(a.payload, BranchDtos.BranchProposal.class);
        } catch (Exception e) {
            log.error("branch_proposal_unreadable approvalId={}", a.id, e);
            throw ApiException.conflict("PAYLOAD_INVALID", "The branch change payload is unreadable");
        }
    }

    private String json(BranchDtos.BranchProposal p) {
        try {
            return mapper.writeValueAsString(p);
        } catch (Exception e) {
            throw ApiException.serviceUnavailable("PAYLOAD_SERIALIZE", "Failed to serialize branch proposal");
        }
    }

    private static String snapshot(String code, String name, String status) {
        return code + "|" + name + "|" + status;
    }

    /** Audit (state hashes only) + outbox, same transaction (AGENTS §2.5). */
    private void record(UUID makerId, UUID checkerId, UUID tenantId, String action, UUID branchId, Object before, Object after) {
        audit.record(makerId, checkerId, tenantId, null, action, "BRANCH", branchId.toString(),
            before == null ? null : audit.hashState(before), after == null ? null : audit.hashState(after), false);
        outbox.record("branch", branchId, action.toLowerCase(Locale.ROOT).replace('_', '.'),
            "{\"branchId\":\"%s\",\"tenantId\":\"%s\"}".formatted(branchId, tenantId));
    }
}
