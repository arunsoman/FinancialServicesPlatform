package com.fsp.core.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.fsp.core.orgunit.OrgUnit;
import com.fsp.core.orgunit.OrgUnitService;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** GAP-1: role changes are four-eyes, non-escalating, version-pinned. */
class RoleAdminServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Actor MAKER = new Actor(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), "maker", TENANT);
    private static final Actor CHECKER = new Actor(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), "checker", TENANT);

    private RoleRepository roles;
    private RbacRepository rbac;
    private RolePermissionRepository rolePermissions;
    private PermissionRepository catalog;
    private PermissionService permissions;
    private ApprovalService approvals;
    private ApprovalRequestRepository approvalRows;
    private final ObjectMapper mapper = new ObjectMapper();
    private RoleAdminService svc;
    private Role checkerRole;
    private MenuAccessService menuAccess;
    private RoleMenuGrantRepository menuGrants;
    private OrgUnitService orgUnits;

    @BeforeEach
    void setUp() {
        roles = mock(RoleRepository.class);
        rbac = mock(RbacRepository.class);
        rolePermissions = mock(RolePermissionRepository.class);
        catalog = mock(PermissionRepository.class);
        TenantRepository tenants = mock(TenantRepository.class);
        permissions = mock(PermissionService.class);
        approvals = mock(ApprovalService.class);
        approvalRows = mock(ApprovalRequestRepository.class);
        menuAccess = mock(MenuAccessService.class);
        menuGrants = mock(RoleMenuGrantRepository.class);
        orgUnits = mock(OrgUnitService.class);
        svc = new RoleAdminService(roles, rbac, rolePermissions, catalog, tenants, permissions, approvals, approvalRows,
            mock(AuditService.class), mock(OutboxService.class), mapper, menuAccess, menuGrants, orgUnits);
        when(menuAccess.portalOf(TENANT)).thenReturn("manufacturer");
        when(menuAccess.forActor(any(), eq(TENANT), eq("manufacturer")))
            .thenReturn(new MenuAccessService.MenuAccess("manufacturer", false, Set.of("users/list:VIEW", "users/list:ACTIVE")));
        when(menuGrants.cellsForRole(any())).thenReturn(List.of());

        when(tenants.existsById(TENANT)).thenReturn(true);
        when(catalog.existsById(anyString())).thenAnswer(i -> !"bogus.key".equals(i.getArgument(0)));
        checkerRole = new Role();
        checkerRole.id = UUID.randomUUID();
        checkerRole.tenantId = TENANT;
        checkerRole.key = "TENANT_CHECKER";
        checkerRole.name = "Tenant Checker";
        checkerRole.scope = "TENANT";
        checkerRole.version = 3;
        when(roles.findById(checkerRole.id)).thenReturn(Optional.of(checkerRole));
        when(rbac.permissionKeysForRole(checkerRole.id)).thenReturn(List.of("je.approve", "kyc.approve"));
        when(approvalRows.findByTargetResourceIdAndStatus(any(), anyString())).thenReturn(List.of());
        when(approvalRows.findByTenantIdAndStatusOrderByRequestedAtDesc(any(), anyString())).thenReturn(List.of());
        when(permissions.effectiveKeys(any(), eq(TENANT))).thenReturn(Set.of("role.manage", "period.open", "je.approve", "kyc.approve"));
        when(approvals.open(any(), anyString(), any(), any(), anyString(), anyString())).thenAnswer(i -> {
            ApprovalRequest a = new ApprovalRequest();
            a.id = UUID.randomUUID();
            a.tenantId = i.getArgument(0);
            a.operationType = i.getArgument(1);
            a.targetResourceId = i.getArgument(2);
            a.payload = i.getArgument(5);
            a.makerId = MAKER.personId();
            return a;
        });
    }

    @Test
    void updateIsStagedNotApplied() {
        RoleDtos.RoleChange change = svc.requestUpdate(TENANT, checkerRole.id,
            new RoleDtos.UpdateRole(null, null, null, null, List.of("je.approve", "kyc.approve", "period.open"), null, 3L), MAKER);

        assertThat(change.operationType()).isEqualTo(ApprovalRequest.OpType.ROLE_UPDATE);
        assertThat(change.pendingApprovalId()).isNotNull();
        verify(rolePermissions, never()).save(any());
        verify(rolePermissions, never()).deleteById(any());
        verify(roles, never()).bumpVersion(any(), any(Long.class), anyString());
    }

    @Test
    void makerCannotGrantAKeyTheyDoNotHold() {
        assertThatThrownBy(() -> svc.requestUpdate(TENANT, checkerRole.id,
            new RoleDtos.UpdateRole(null, null, null, null, List.of("je.approve", "kyc.approve", "org.create"), null, 3L), MAKER))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ROLE_NOT_ASSIGNABLE"));
    }

    @Test
    void unknownPermissionKeyIsRefused() {
        assertThatThrownBy(() -> svc.requestCreate(TENANT, new RoleDtos.CreateRole("AUDITOR", "Auditor", "Reads the books", null, null, List.of("bogus.key"), null), MAKER))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("PERMISSION_UNKNOWN"));
    }

    @Test
    void staleExpectedVersionIsRefused() {
        assertThatThrownBy(() -> svc.requestUpdate(TENANT, checkerRole.id,
            new RoleDtos.UpdateRole(null, null, null, null, List.of("je.approve"), null, 2L), MAKER))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("STALE_WRITE"));
    }

    @Test
    void anchorRolesCannotBeDeleted() {
        assertThatThrownBy(() -> svc.requestDelete(TENANT, checkerRole.id, 3L, MAKER))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ROLE_PROTECTED"));
    }

    @Test
    void assignedRoleCannotBeDeleted() {
        checkerRole.key = "AUDITOR";
        when(rbac.countByRoleId(checkerRole.id)).thenReturn(2L);
        assertThatThrownBy(() -> svc.requestDelete(TENANT, checkerRole.id, 3L, MAKER))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ROLE_IN_USE"));
    }

    @Test
    void approvedUpdateAppliesExactlyTheDiffAndBumpsVersion() throws Exception {
        ApprovalRequest a = approval(ApprovalRequest.OpType.ROLE_UPDATE,
            new RoleDtos.RoleProposal("TENANT_CHECKER", "Tenant Checker", List.of("kyc.approve", "period.open"), 3L));
        when(roles.bumpVersion(checkerRole.id, 3L, "Tenant Checker")).thenReturn(1);

        svc.applyUpdate(a, CHECKER);

        ArgumentCaptor<RolePermission> granted = ArgumentCaptor.forClass(RolePermission.class);
        verify(rolePermissions).save(granted.capture());
        assertThat(granted.getValue().permissionKey).isEqualTo("period.open");
        verify(rolePermissions).deleteById(new RolePermissionId(checkerRole.id, "je.approve"));
        verify(roles).bumpVersion(checkerRole.id, 3L, "Tenant Checker");
    }

    @Test
    void approvalOfAChangeBasedOnAnOldVersionIsRefused() throws Exception {
        ApprovalRequest a = approval(ApprovalRequest.OpType.ROLE_UPDATE,
            new RoleDtos.RoleProposal("TENANT_CHECKER", "Tenant Checker", List.of("kyc.approve"), 2L));
        assertThatThrownBy(() -> svc.applyUpdate(a, CHECKER))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("STALE_WRITE"));
        verify(rolePermissions, never()).save(any());
    }

    @Test
    void checkerNeedNotHoldTheKeysTheyApprove() throws Exception {
        // settled design (4a99164): requireHeld applies at PROPOSAL time only — a checker who
        // doesn't yet hold a key may still approve granting it (otherwise legitimate role
        // upgrades would deadlock: no checker could hold period.open to approve adding it).
        when(permissions.effectiveKeys(eq(CHECKER), eq(TENANT))).thenReturn(Set.of("role.manage", "je.approve"));
        when(roles.bumpVersion(checkerRole.id, 3L, "Tenant Checker")).thenReturn(1);
        ApprovalRequest a = approval(ApprovalRequest.OpType.ROLE_UPDATE,
            new RoleDtos.RoleProposal("TENANT_CHECKER", "Tenant Checker", List.of("je.approve", "period.open"), 3L));

        svc.applyUpdate(a, CHECKER);

        ArgumentCaptor<RolePermission> granted = ArgumentCaptor.forClass(RolePermission.class);
        verify(rolePermissions).save(granted.capture());
        assertThat(granted.getValue().permissionKey).isEqualTo("period.open");
    }

    // ---------------------------------------------------------- menu cells, description, units

    @Test
    void createStagesMenuCellsDescriptionAndUnitsWithoutWritingThem() throws Exception {
        UUID branch = UUID.randomUUID();
        UUID dept = UUID.randomUUID();
        when(orgUnits.requireActive(OrgUnitService.Kind.BRANCH, TENANT, branch)).thenReturn(unit(branch));
        when(orgUnits.requireActive(OrgUnitService.Kind.DEPARTMENT, TENANT, dept)).thenReturn(unit(dept));

        RoleDtos.RoleChange change = svc.requestCreate(TENANT, new RoleDtos.CreateRole("TELLER_SUPERVISOR", "Teller Supervisor",
            "Supervises the tellers", branch, dept, List.of("je.approve"), List.of("users/list:ACTIVE", "users/list:VIEW")), MAKER);

        assertThat(change.operationType()).isEqualTo(ApprovalRequest.OpType.ROLE_CREATE);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(approvals).open(eq(TENANT), eq(ApprovalRequest.OpType.ROLE_CREATE), any(), eq(MAKER), anyString(), payload.capture());
        RoleDtos.RoleProposal p = mapper.readValue(payload.getValue(), RoleDtos.RoleProposal.class);
        assertThat(p.description()).isEqualTo("Supervises the tellers");
        assertThat(p.branchId()).isEqualTo(branch);
        assertThat(p.departmentId()).isEqualTo(dept);
        assertThat(p.portal()).isEqualTo("manufacturer");
        assertThat(p.menuCells()).containsExactly("users/list:ACTIVE", "users/list:VIEW");
        verify(menuAccess).requirePublished("manufacturer", List.of("users/list:ACTIVE", "users/list:VIEW"));
        verify(menuGrants, never()).replace(any(), anyString(), any());
    }

    @Test
    void makerCannotGrantAMenuCellTheyDoNotHold() {
        assertThatThrownBy(() -> svc.requestCreate(TENANT, new RoleDtos.CreateRole("AUDITOR", "Auditor", "Reads the books",
            null, null, List.of(), List.of("users/list:DELETE")), MAKER))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ROLE_MENU_NOT_ASSIGNABLE"));
        verify(approvals, never()).open(any(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void malformedMenuCellIsRefused() {
        assertThatThrownBy(() -> svc.requestCreate(TENANT, new RoleDtos.CreateRole("AUDITOR", "Auditor", "Reads the books",
            null, null, List.of(), List.of("users/list:EXECUTE")), MAKER))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("MENU_CELL_INVALID"));
    }

    @Test
    void descriptionIsRequiredOnCreate() {
        assertThatThrownBy(() -> svc.requestCreate(TENANT, new RoleDtos.CreateRole("AUDITOR", "Auditor", " ",
            null, null, List.of(), List.of()), MAKER))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ROLE_DESCRIPTION_INVALID"));
    }

    @Test
    void branchIsRequiredOnceTheOrganizationHasBranches() {
        when(orgUnits.hasActive(OrgUnitService.Kind.BRANCH, TENANT)).thenReturn(true);
        assertThatThrownBy(() -> svc.requestCreate(TENANT, new RoleDtos.CreateRole("AUDITOR", "Auditor", "Reads the books",
            null, null, List.of(), List.of()), MAKER))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("BRANCH_REQUIRED"));
    }

    @Test
    void updateThatOnlyMovesMenuCellsIsStaged() {
        when(menuGrants.cellsForRole(checkerRole.id)).thenReturn(List.of("users/list:VIEW"));
        RoleDtos.RoleChange change = svc.requestUpdate(TENANT, checkerRole.id, new RoleDtos.UpdateRole(null, null, null, null,
            null, List.of("users/list:ACTIVE", "users/list:VIEW"), 3L), MAKER);
        assertThat(change.operationType()).isEqualTo(ApprovalRequest.OpType.ROLE_UPDATE);
        verify(menuGrants, never()).replace(any(), anyString(), any());
    }

    @Test
    void approvedCreateWritesTheMenuCells() throws Exception {
        ApprovalRequest a = approval(ApprovalRequest.OpType.ROLE_CREATE, new RoleDtos.RoleProposal("AUDITOR", "Auditor",
            List.of(), null, "Reads the books", null, null, "manufacturer", List.of("users/list:ACTIVE", "users/list:VIEW")));
        a.targetResourceId = UUID.randomUUID();
        when(roles.findByTenantId(TENANT)).thenReturn(List.of());

        svc.applyCreate(a, CHECKER);

        verify(menuAccess).requirePublished("manufacturer", List.of("users/list:ACTIVE", "users/list:VIEW"));
        verify(menuGrants).replace(a.targetResourceId, "manufacturer", List.of("users/list:ACTIVE", "users/list:VIEW"));
    }

    @Test
    void approvalAfterTheMenuChangedIsRefusedAndWritesNothing() throws Exception {
        doThrow(ApiException.conflict("MENU_CELL_UNKNOWN", "gone"))
            .when(menuAccess).requirePublished(eq("manufacturer"), any());
        ApprovalRequest a = approval(ApprovalRequest.OpType.ROLE_UPDATE, new RoleDtos.RoleProposal("TENANT_CHECKER",
            "Tenant Checker", List.of("je.approve", "kyc.approve"), 3L, null, null, null, "manufacturer",
            List.of("users/list:VIEW")));

        assertThatThrownBy(() -> svc.applyUpdate(a, CHECKER))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("MENU_CHANGED"));
        verify(menuGrants, never()).replace(any(), anyString(), any());
        verify(roles, never()).bumpVersionWithDetails(any(), any(Long.class), anyString(), any(), any(), any());
    }

    @Test
    void approvedUpdateBumpsTheVersionBeforeRewritingCells() throws Exception {
        ApprovalRequest a = approval(ApprovalRequest.OpType.ROLE_UPDATE, new RoleDtos.RoleProposal("TENANT_CHECKER",
            "Tenant Checker", List.of("je.approve", "kyc.approve"), 3L, "Checks", null, null, "manufacturer",
            List.of("users/list:VIEW")));
        when(roles.bumpVersionWithDetails(checkerRole.id, 3L, "Tenant Checker", "Checks", null, null)).thenReturn(1);

        svc.applyUpdate(a, CHECKER);

        var order = org.mockito.Mockito.inOrder(roles, menuGrants);
        order.verify(roles).bumpVersionWithDetails(checkerRole.id, 3L, "Tenant Checker", "Checks", null, null);
        order.verify(menuGrants).replace(checkerRole.id, "manufacturer", List.of("users/list:VIEW"));
    }

    private static OrgUnit unit(UUID id) {
        OrgUnit u = new com.fsp.core.orgunit.Branch();
        u.id = id;
        u.tenantId = TENANT;
        u.status = OrgUnit.ACTIVE;
        return u;
    }

    private ApprovalRequest approval(String op, RoleDtos.RoleProposal p) throws Exception {
        ApprovalRequest a = new ApprovalRequest();
        a.id = UUID.randomUUID();
        a.tenantId = TENANT;
        a.operationType = op;
        a.targetResourceId = checkerRole.id;
        a.makerId = MAKER.personId();
        a.payload = mapper.writeValueAsString(p);
        return a;
    }
}
