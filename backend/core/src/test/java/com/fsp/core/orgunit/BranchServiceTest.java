package com.fsp.core.orgunit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** org.branches stage 2: branch writes are four-eyes, version-pinned, tenant-scoped and name-unique. */
class BranchServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MAKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CHECKER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Actor MAKER = new Actor(MAKER_ID, "maker", TENANT);
    private static final Actor CHECKER = new Actor(CHECKER_ID, "checker", TENANT);

    private BranchRepository branches;
    private PermissionService permissions;
    private ApprovalService approvals;
    private ApprovalRequestRepository approvalRows;
    private PersonRepository persons;
    private final ObjectMapper mapper = new ObjectMapper();
    private BranchService svc;

    @BeforeEach
    void setUp() {
        branches = mock(BranchRepository.class);
        TenantRepository tenants = mock(TenantRepository.class);
        permissions = mock(PermissionService.class);
        approvals = mock(ApprovalService.class);
        approvalRows = mock(ApprovalRequestRepository.class);
        persons = mock(PersonRepository.class);
        svc = new BranchService(branches, tenants, permissions, approvals, approvalRows, persons,
            mock(AuditService.class), mock(OutboxService.class), mapper);
        when(tenants.existsById(TENANT)).thenReturn(true);
        when(tenants.existsById(OTHER_TENANT)).thenReturn(true);
        when(branches.liveIdsByName(any(), anyString())).thenReturn(List.of());
        when(branches.saveAndFlush(any(Branch.class))).thenAnswer(i -> i.getArgument(0));
        when(branches.nextCodeNumber()).thenReturn(7L);
        when(approvalRows.findByTargetResourceIdAndStatus(any(), anyString())).thenReturn(List.of());
        when(approvalRows.findByTenantIdAndOperationTypeAndStatusInOrderByRequestedAtDesc(any(), anyString(), any()))
            .thenReturn(List.of());
        when(approvalRows.findByTargetResourceIdInAndStatus(any(), anyString())).thenReturn(List.of());
        when(approvals.open(any(), anyString(), any(), any(), anyString(), anyString())).thenAnswer(i -> {
            ApprovalRequest a = new ApprovalRequest();
            a.id = UUID.randomUUID();
            a.operationType = i.getArgument(1);
            a.targetResourceId = i.getArgument(2);
            return a;
        });
        when(persons.findAllById(any())).thenReturn(List.of(person(MAKER_ID, "Johannes Kapuka", false),
            person(CHECKER_ID, "Anna Shikongo", true)));
        when(persons.findById(MAKER_ID)).thenReturn(Optional.of(person(MAKER_ID, "Johannes Kapuka", false)));
        when(persons.findById(CHECKER_ID)).thenReturn(Optional.of(person(CHECKER_ID, "Anna Shikongo", true)));
        when(permissions.effectiveKeys(any(), any())).thenReturn(Set.of("role.view", "role.manage"));
    }

    // ------------------------------------------------------------------ create

    @Test
    void createFilesAPendingBranchAndOpensItsApproval() throws Exception {
        BranchDtos.BranchChange c = svc.requestCreate(TENANT, new BranchDtos.CreateBranch("  Oshakati   Main "), MAKER);

        ArgumentCaptor<Branch> saved = ArgumentCaptor.forClass(Branch.class);
        verify(branches).saveAndFlush(saved.capture());
        Branch b = saved.getValue();
        assertThat(b.status).isEqualTo(Branch.PENDING);
        assertThat(b.name).isEqualTo("Oshakati Main");
        assertThat(b.code).isEqualTo("BR-0007");
        assertThat(b.makerId).isEqualTo(MAKER_ID);
        assertThat(b.tenantId).isEqualTo(TENANT);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(approvals).open(eq(TENANT), eq(ApprovalRequest.OpType.BRANCH_CREATE), eq(b.id), eq(MAKER), anyString(), payload.capture());
        assertThat(mapper.readValue(payload.getValue(), BranchDtos.BranchProposal.class))
            .isEqualTo(new BranchDtos.BranchProposal(0L, "Oshakati Main"));
        assertThat(c.branchId()).isEqualTo(b.id);
        assertThat(c.operationType()).isEqualTo(ApprovalRequest.OpType.BRANCH_CREATE);
    }

    @Test
    void createNeedsRoleManage() {
        doThrow(ApiException.forbidden("PERMISSION_DENIED", "no")).when(permissions).require(MAKER, "role.manage", TENANT);
        assertCode(() -> svc.requestCreate(TENANT, new BranchDtos.CreateBranch("Oshakati"), MAKER), "PERMISSION_DENIED");
        verify(branches, never()).saveAndFlush(any());
    }

    @Test
    void machineActorCannotFileABranch() {
        Actor machine = new Actor(null, "plugin-kyc", TENANT, Actor.ActorType.MACHINE, "plugin-kyc");
        assertCode(() -> svc.requestCreate(TENANT, new BranchDtos.CreateBranch("Oshakati"), machine), "MACHINE_MAKER");
    }

    @Test
    void createRejectsAnInvalidName() {
        assertCode(() -> svc.requestCreate(TENANT, new BranchDtos.CreateBranch("W"), MAKER), "BRANCH_NAME_INVALID");
        assertCode(() -> svc.requestCreate(TENANT, new BranchDtos.CreateBranch("<script>"), MAKER), "BRANCH_NAME_INVALID");
        assertCode(() -> svc.requestCreate(TENANT, new BranchDtos.CreateBranch("x".repeat(81)), MAKER), "BRANCH_NAME_INVALID");
    }

    @Test
    void createRefusesANameInUseOrAlreadyProposed() {
        when(branches.liveIdsByName(TENANT, "walvis bay")).thenReturn(List.of(UUID.randomUUID()));
        assertCode(() -> svc.requestCreate(TENANT, new BranchDtos.CreateBranch("Walvis Bay"), MAKER), "BRANCH_NAME_TAKEN");

        ApprovalRequest rename = approval(ApprovalRequest.OpType.BRANCH_UPDATE, UUID.randomUUID(), 3, "Swakopmund Town");
        when(approvalRows.findByTenantIdAndOperationTypeAndStatusInOrderByRequestedAtDesc(any(), eq(ApprovalRequest.OpType.BRANCH_UPDATE), any()))
            .thenReturn(List.of(rename));
        assertCode(() -> svc.requestCreate(TENANT, new BranchDtos.CreateBranch("swakopmund town"), MAKER), "BRANCH_NAME_TAKEN");
    }

    @Test
    void aConcurrentFilingOfTheSameNameIsA409NotA500() {
        when(branches.saveAndFlush(any(Branch.class))).thenThrow(new DataIntegrityViolationException("ux_branches_tenant_live_name"));
        assertCode(() -> svc.requestCreate(TENANT, new BranchDtos.CreateBranch("Oshakati"), MAKER), "BRANCH_NAME_TAKEN");
        verify(approvals, never()).open(any(), anyString(), any(), any(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- update / delete

    @Test
    void renameIsVersionPinnedAndChangesNothingUntilApproved() throws Exception {
        Branch b = branch(TENANT, OrgUnit.ACTIVE, 4, "Walvis Bay");
        when(branches.touchActive(eq(b.id), eq(4L), any())).thenReturn(1);

        svc.requestUpdate(TENANT, b.id, new BranchDtos.UpdateBranch("Walvis Bay Harbour", 4L), MAKER);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(approvals).open(eq(TENANT), eq(ApprovalRequest.OpType.BRANCH_UPDATE), eq(b.id), eq(MAKER), anyString(), payload.capture());
        assertThat(mapper.readValue(payload.getValue(), BranchDtos.BranchProposal.class))
            .isEqualTo(new BranchDtos.BranchProposal(5L, "Walvis Bay Harbour"));
        verify(branches, never()).rename(any(), anyLong(), anyString(), any());
    }

    @Test
    void renameRefusesStaleVersionsPendingChangesAndUnapprovedBranches() {
        Branch b = branch(TENANT, OrgUnit.ACTIVE, 4, "Walvis Bay");
        assertCode(() -> svc.requestUpdate(TENANT, b.id, new BranchDtos.UpdateBranch("Walvis", 3L), MAKER), "STALE_WRITE");
        assertCode(() -> svc.requestUpdate(TENANT, b.id, new BranchDtos.UpdateBranch("Walvis", null), MAKER), "VERSION_REQUIRED");

        when(approvalRows.findByTargetResourceIdAndStatus(b.id, ApprovalRequest.Status.PENDING_CHECKER))
            .thenReturn(List.of(approval(ApprovalRequest.OpType.BRANCH_DELETE, b.id, 4, "Walvis Bay")));
        assertCode(() -> svc.requestUpdate(TENANT, b.id, new BranchDtos.UpdateBranch("Walvis", 4L), MAKER), "BRANCH_CHANGE_PENDING");

        Branch pending = branch(TENANT, Branch.PENDING, 0, "Oshakati");
        assertCode(() -> svc.requestUpdate(TENANT, pending.id, new BranchDtos.UpdateBranch("Osh", 0L), MAKER), "BRANCH_STATE");
    }

    @Test
    void aConcurrentFilerLosesThePinRaceWith409() {
        Branch b = branch(TENANT, OrgUnit.ACTIVE, 4, "Walvis Bay");
        when(branches.touchActive(eq(b.id), eq(4L), any())).thenReturn(0); // another maker filed first
        assertCode(() -> svc.requestUpdate(TENANT, b.id, new BranchDtos.UpdateBranch("Walvis Port", 4L), MAKER), "STALE_WRITE");
        verify(approvals, never()).open(any(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void anotherOrganizationsBranchIsNotFound() {
        Branch foreign = branch(OTHER_TENANT, OrgUnit.ACTIVE, 1, "Rundu");
        assertCode(() -> svc.requestUpdate(TENANT, foreign.id, new BranchDtos.UpdateBranch("Rundu 2", 1L), MAKER), "BRANCH_NOT_FOUND");
        assertCode(() -> svc.get(TENANT, foreign.id, MAKER), "BRANCH_NOT_FOUND");
        assertCode(() -> svc.requestDelete(TENANT, foreign.id, 1L, MAKER), "BRANCH_NOT_FOUND");
    }

    @Test
    void deleteIsRefusedWhileRolesUseTheBranch() {
        Branch b = branch(TENANT, OrgUnit.ACTIVE, 2, "Windhoek Head Office");
        when(branches.countRoles(b.id)).thenReturn(3L);
        ApiException e = catchApi(() -> svc.requestDelete(TENANT, b.id, 2L, MAKER));
        assertThat(e.getCode()).isEqualTo("BRANCH_IN_USE");
        assertThat(e.getMessage()).contains("3 roles are assigned");
        verify(branches, never()).touchActive(any(), anyLong(), any());
    }

    @Test
    void deleteFilesAVersionPinnedApproval() {
        Branch b = branch(TENANT, OrgUnit.ACTIVE, 2, "Keetmanshoop");
        when(branches.touchActive(eq(b.id), eq(2L), any())).thenReturn(1);
        BranchDtos.BranchChange c = svc.requestDelete(TENANT, b.id, 2L, MAKER);
        assertThat(c.operationType()).isEqualTo(ApprovalRequest.OpType.BRANCH_DELETE);
        verify(branches, never()).archive(any(), anyLong(), any());
    }

    // ------------------------------------------------------------------ apply (checker)

    @Test
    void approvedCreationActivatesWithTheCheckerRecorded() {
        UUID id = UUID.randomUUID();
        ApprovalRequest a = approval(ApprovalRequest.OpType.BRANCH_CREATE, id, 0, "Oshakati");
        when(branches.decideCreation(eq(id), eq(0L), eq(OrgUnit.ACTIVE), eq(CHECKER_ID), isNull(), any())).thenReturn(1);
        svc.decideCreate(a, true, CHECKER, null);
        verify(branches).decideCreation(eq(id), eq(0L), eq(OrgUnit.ACTIVE), eq(CHECKER_ID), isNull(), any());
    }

    @Test
    void rejectedCreationKeepsTheReason() {
        UUID id = UUID.randomUUID();
        ApprovalRequest a = approval(ApprovalRequest.OpType.BRANCH_CREATE, id, 0, "Oshakati");
        when(branches.decideCreation(eq(id), eq(0L), eq(Branch.REJECTED), eq(CHECKER_ID), eq("Opens next year"), any())).thenReturn(1);
        svc.decideCreate(a, false, CHECKER, "  Opens next year ");
        verify(branches).decideCreation(eq(id), eq(0L), eq(Branch.REJECTED), eq(CHECKER_ID), eq("Opens next year"), any());
    }

    @Test
    void applyingAStaleChangeFailsSoTheApprovalRollsBack() {
        ApprovalRequest a = approval(ApprovalRequest.OpType.BRANCH_UPDATE, UUID.randomUUID(), 5, "Walvis Bay Harbour");
        when(branches.rename(any(), anyLong(), anyString(), any())).thenReturn(0);
        assertCode(() -> svc.applyUpdate(a, CHECKER), "STALE_WRITE");
    }

    @Test
    void aRenameRacingAnotherForTheSameNameIsA409() {
        ApprovalRequest a = approval(ApprovalRequest.OpType.BRANCH_UPDATE, UUID.randomUUID(), 5, "Walvis Bay Harbour");
        when(branches.rename(any(), anyLong(), anyString(), any())).thenThrow(new DataIntegrityViolationException("dup"));
        assertCode(() -> svc.applyUpdate(a, CHECKER), "BRANCH_NAME_TAKEN");
    }

    @Test
    void approvedDeletionRechecksRoleUseThenArchives() {
        Branch b = branch(TENANT, OrgUnit.ACTIVE, 3, "Keetmanshoop");
        ApprovalRequest a = approval(ApprovalRequest.OpType.BRANCH_DELETE, b.id, 3, "Keetmanshoop");
        when(branches.countRoles(b.id)).thenReturn(1L);
        assertCode(() -> svc.applyDelete(a, CHECKER), "BRANCH_IN_USE");

        when(branches.countRoles(b.id)).thenReturn(0L);
        when(branches.archive(eq(b.id), eq(3L), any())).thenReturn(1);
        svc.applyDelete(a, CHECKER);
        verify(branches).archive(eq(b.id), eq(3L), any());
    }

    // ------------------------------------------------------------------ reads

    @Test
    void listPushesTheViewAndSearchToSqlAndNamesThePeople() {
        Branch seeded = branch(TENANT, OrgUnit.ACTIVE, 0, "Head Office");
        seeded.makerId = null;
        Branch filed = branch(TENANT, OrgUnit.ACTIVE, 2, "Walvis Bay");
        filed.makerId = MAKER_ID;
        filed.checkerId = CHECKER_ID;
        when(branches.page(eq(TENANT), eq("CURRENT"), eq("wal"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(seeded, filed), PageRequest.of(0, 20), 2));

        BranchDtos.BranchPage p = svc.list(TENANT, 0, 20, "name,asc", " WAL ", "current", CHECKER);

        assertThat(p.totalElements()).isEqualTo(2);
        assertThat(p.sort()).isEqualTo("name,asc");
        assertThat(p.items().get(0).makerName()).isEqualTo(BranchService.SYSTEM);
        assertThat(p.items().get(1).makerName()).isEqualTo("Johannes Kapuka");
        assertThat(p.items().get(1).checkerName()).isEqualTo("Anna Shikongo");
        verify(permissions).requireAny(CHECKER, List.of("role.view", "role.manage"), TENANT);
    }

    @Test
    void listRefusesUnknownStatusesAndOversizedPages() {
        assertCode(() -> svc.list(TENANT, 0, 20, null, null, "BOGUS", CHECKER), "BRANCH_STATUS_INVALID");
        assertCode(() -> svc.list(TENANT, 0, 500, null, null, "CURRENT", CHECKER), "PAGE_INVALID");
        assertCode(() -> svc.list(TENANT, 0, 20, "password,asc", null, "CURRENT", CHECKER), "SORT_INVALID");
    }

    @Test
    void theCallerSeesWhetherTheyMayDecideAPendingChange() {
        Branch b = branch(TENANT, OrgUnit.ACTIVE, 5, "Swakopmund");
        ApprovalRequest rename = approval(ApprovalRequest.OpType.BRANCH_UPDATE, b.id, 5, "Swakopmund Town");
        when(approvalRows.findByTargetResourceIdInAndStatus(any(), anyString())).thenReturn(List.of(rename));

        BranchDtos.PendingChange asChecker = svc.get(TENANT, b.id, CHECKER).pending();
        assertThat(asChecker.canDecide()).isTrue();
        assertThat(asChecker.proposedName()).isEqualTo("Swakopmund Town");
        assertThat(asChecker.requestedByName()).isEqualTo("Johannes Kapuka");

        BranchDtos.PendingChange asMaker = svc.get(TENANT, b.id, MAKER).pending();
        assertThat(asMaker.canDecide()).isFalse();
        assertThat(asMaker.blockedReason()).isEqualTo("OWN_REQUEST");

        UUID viewerId = UUID.randomUUID();
        when(persons.findById(viewerId)).thenReturn(Optional.of(person(viewerId, "Viewer", true)));
        when(permissions.effectiveKeys(any(), any())).thenReturn(Set.of("role.view"));
        assertThat(svc.get(TENANT, b.id, new Actor(viewerId, "viewer", TENANT)).pending().blockedReason()).isEqualTo("NO_PERMISSION");

        UUID unflagged = UUID.randomUUID();
        when(persons.findById(unflagged)).thenReturn(Optional.of(person(unflagged, "Not A Checker", false)));
        when(permissions.effectiveKeys(any(), any())).thenReturn(Set.of("role.manage"));
        assertThat(svc.get(TENANT, b.id, new Actor(unflagged, "x", TENANT)).pending().blockedReason()).isEqualTo("NOT_CHECKER");
    }

    // ------------------------------------------------------------------ helpers

    private Branch branch(UUID tenant, String status, long version, String name) {
        Branch b = new Branch();
        b.id = UUID.randomUUID();
        b.tenantId = tenant;
        b.code = "BR-0001";
        b.name = name;
        b.status = status;
        b.version = version;
        b.createdAt = Instant.parse("2026-11-20T08:30:00Z");
        b.updatedAt = b.createdAt;
        when(branches.findById(b.id)).thenReturn(Optional.of(b));
        return b;
    }

    private ApprovalRequest approval(String type, UUID target, long base, String name) {
        ApprovalRequest a = new ApprovalRequest();
        a.id = UUID.randomUUID();
        a.tenantId = TENANT;
        a.operationType = type;
        a.targetResourceId = target;
        a.makerId = MAKER_ID;
        a.requestedAt = Instant.parse("2026-11-21T09:00:00Z");
        try {
            a.payload = mapper.writeValueAsString(new BranchDtos.BranchProposal(base, name));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return a;
    }

    private static Person person(UUID id, String fullName, boolean checker) {
        Person p = new Person();
        p.id = id;
        p.username = fullName.toLowerCase().replace(' ', '.');
        p.fullName = fullName;
        p.isChecker = checker;
        p.status = "ACTIVE";
        return p;
    }

    private static ApiException catchApi(ThrowableAssert.ThrowingCallable call) {
        try {
            call.call();
        } catch (ApiException e) {
            return e;
        } catch (Throwable t) {
            throw new AssertionError("expected ApiException, got " + t, t);
        }
        throw new AssertionError("expected ApiException, nothing thrown");
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(code));
    }
}
