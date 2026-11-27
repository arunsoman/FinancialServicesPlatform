package com.fsp.core.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.approval.ApprovalRequestRepository;
import com.fsp.core.approval.ApprovalService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.rbac.Permission;
import com.fsp.core.rbac.PermissionRepository;
import com.fsp.core.rbac.PermissionService;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Widget registration + layout draft/publish flow (plans/widget-harness-prd.md §4). */
class UiHarnessServiceTest {

    private static final Actor ADMIN = actor("30000000-0000-0000-0000-000000000301", "maker");
    private static final Actor CHECKER = actor("30000000-0000-0000-0000-000000000302", "checker");
    private static final UUID ROOT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static Actor actor(String id, String name) {
        return new Actor(UUID.fromString(id), name, ROOT);
    }

    /** A permissive PermissionService mock: require/requireAny are no-ops by default. */
    private static PermissionService permissivePermissions() {
        return org.mockito.Mockito.mock(PermissionService.class);
    }

    // ---------- widget registry ----------

    @Test
    void rejectsUnknownPermissionCell() {
        PermissionRepository perms = org.mockito.Mockito.mock(PermissionRepository.class);
        when(perms.findById(any())).thenReturn(Optional.empty());
        WidgetRegistryService svc = new WidgetRegistryService(null, perms, permissivePermissions());

        var dto = new UiDtos.WidgetRegistration("kyc.orgs", "1.0.0", "Org tree", "kyc",
            "widgets/kyc-orgs", List.of("admin"), List.of("kyc.org.view"), null);
        assertThatThrownBy(() -> svc.register(dto, ADMIN))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("UI_WIDGET_UNKNOWN_CELL"));
    }

    @Test
    void rejectsBadWidgetId() {
        WidgetRegistryService svc = new WidgetRegistryService(
            org.mockito.Mockito.mock(UiWidgetRepository.class),
            org.mockito.Mockito.mock(PermissionRepository.class), permissivePermissions());

        var dto = new UiDtos.WidgetRegistration("Bad Id", "1.0.0", "l", "c", "b", List.of(), List.of(), null);
        assertThatThrownBy(() -> svc.register(dto, ADMIN))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("UI_WIDGET_ID"));
    }

    @Test
    void acceptsWhenCellsExist() {
        UiWidgetRepository repo = org.mockito.Mockito.mock(UiWidgetRepository.class);
        PermissionRepository perms = org.mockito.Mockito.mock(PermissionRepository.class);
        when(perms.findById("kyc.org.view")).thenReturn(Optional.of(new Permission()));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        WidgetRegistryService svc = new WidgetRegistryService(repo, perms, permissivePermissions());

        var dto = new UiDtos.WidgetRegistration("kyc.orgs", "1.0.0", "Org tree", "kyc",
            "widgets/kyc-orgs", List.of("admin"), List.of("kyc.org.view"), null);
        UiWidget saved = svc.register(dto, ADMIN);
        assertThat(saved.id).isEqualTo("kyc.orgs");
        assertThat(saved.requires).isEqualTo("[\"kyc.org.view\"]");
    }

    // ---------- portal layout ----------

    private PortalLayoutService layoutService(UiLayoutRepository repo,
                                              ApprovalRequestRepository approvals,
                                              ApprovalService approvalService) {
        TenantRepository tenants = org.mockito.Mockito.mock(TenantRepository.class);
        Tenant root = new Tenant();
        ReflectionTestUtils.setField(root, "id", ROOT);
        when(tenants.findByParentTenantId(null)).thenReturn(List.of(root));
        return new PortalLayoutService(repo, tenants, permissivePermissions(), approvalService, approvals,
            new LayoutValidator(), new com.fsp.core.common.KeyLocks());
    }

    @Test
    void submitRefusesWhenNoDraft() {
        UiLayoutRepository repo = org.mockito.Mockito.mock(UiLayoutRepository.class);
        when(repo.findFirstByPortalAndStatus("admin", UiLayout.Status.DRAFT)).thenReturn(Optional.empty());
        ApprovalRequestRepository approvals = org.mockito.Mockito.mock(ApprovalRequestRepository.class);
        PortalLayoutService svc = layoutService(repo, approvals, null);

        assertThatThrownBy(() -> svc.submit("admin", ADMIN))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("UI_LAYOUT_NO_DRAFT"));
    }

    @Test
    void submitConflictsWhenApprovalAlreadyPending() {
        UiLayout draft = draft();
        UiLayoutRepository repo = org.mockito.Mockito.mock(UiLayoutRepository.class);
        when(repo.findFirstByPortalAndStatus("admin", UiLayout.Status.DRAFT)).thenReturn(Optional.of(draft));
        ApprovalRequestRepository approvals = org.mockito.Mockito.mock(ApprovalRequestRepository.class);
        // The draft row is reused across publishes, so it may also carry earlier APPROVED rows;
        // only a PENDING_CHECKER one blocks a new submit.
        when(approvals.existsByOperationTypeAndTargetResourceIdAndStatus(
            ApprovalRequest.OpType.UI_LAYOUT_PUBLISH, draft.id, ApprovalRequest.Status.PENDING_CHECKER)).thenReturn(true);
        PortalLayoutService svc = layoutService(repo, approvals, null);

        assertThatThrownBy(() -> svc.submit("admin", ADMIN))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("APPROVAL_STATE"));
    }

    @Test
    void saveDraftValidatesAndUpserts() {
        UiLayoutRepository repo = org.mockito.Mockito.mock(UiLayoutRepository.class);
        when(repo.findFirstByPortalAndStatus("admin", UiLayout.Status.DRAFT)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PortalLayoutService svc = layoutService(repo,
            org.mockito.Mockito.mock(ApprovalRequestRepository.class), null);

        var req = new UiDtos.LayoutSaveRequest(
            "{\"portal\":\"admin\",\"menus\":[{\"id\":\"m1\",\"label\":\"Main\","
                + "\"screens\":[{\"id\":\"s1\",\"label\":\"Dash\",\"placedWidgets\":[]}]}]}", 1);
        UiLayout saved = svc.saveDraft("admin", req, ADMIN);
        assertThat(saved.portal).isEqualTo("admin");
        assertThat(saved.makerId).isEqualTo(ADMIN.personId());
    }

    @Test
    void saveDraftIsLockedWhilePublishApprovalPending() {
        UiLayout draft = draft();
        UiLayoutRepository repo = org.mockito.Mockito.mock(UiLayoutRepository.class);
        when(repo.findFirstByPortalAndStatus("admin", UiLayout.Status.DRAFT)).thenReturn(Optional.of(draft));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ApprovalRequestRepository approvals = org.mockito.Mockito.mock(ApprovalRequestRepository.class);
        when(approvals.existsByOperationTypeAndTargetResourceIdAndStatus(
            ApprovalRequest.OpType.UI_LAYOUT_PUBLISH, draft.id, ApprovalRequest.Status.PENDING_CHECKER)).thenReturn(true);
        PortalLayoutService svc = layoutService(repo, approvals, null);

        var req = new UiDtos.LayoutSaveRequest(
            "{\"portal\":\"admin\",\"menus\":[{\"id\":\"m1\",\"label\":\"Main\"," +
                "\"screens\":[{\"id\":\"s1\",\"label\":\"Dash\",\"placedWidgets\":[]}]}]}", 1);
        // Maker-checker integrity: while a publish approval is PENDING_CHECKER, no one (not
        // even the maker) may mutate the draft — the checker must decide the submitted snapshot.
        assertThatThrownBy(() -> svc.saveDraft("admin", req, ADMIN))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("APPROVAL_STATE"));
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void saveDraftAllowsEditingWhenNoApprovalPending() {
        UiLayout draft = draft();
        UiLayoutRepository repo = org.mockito.Mockito.mock(UiLayoutRepository.class);
        when(repo.findFirstByPortalAndStatus("admin", UiLayout.Status.DRAFT)).thenReturn(Optional.of(draft));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ApprovalRequestRepository approvals = org.mockito.Mockito.mock(ApprovalRequestRepository.class);
        PortalLayoutService svc = layoutService(repo, approvals, null);

        var req = new UiDtos.LayoutSaveRequest(
            "{\"portal\":\"admin\",\"menus\":[{\"id\":\"m1\",\"label\":\"Main\"," +
                "\"screens\":[{\"id\":\"s1\",\"label\":\"Dash\",\"placedWidgets\":[]}]}]}", 1);
        assertThat(svc.saveDraft("admin", req, ADMIN)).isSameAs(draft);
    }

    @Test
    void handlerPublishesSubmittedSnapshotNotLaterDraftEdit() {
        UiLayout draft = draft();
        UiLayoutRepository repo = org.mockito.Mockito.mock(UiLayoutRepository.class);
        when(repo.findById(draft.id)).thenReturn(Optional.of(draft));
        when(repo.findFirstByPortalAndStatus("admin", UiLayout.Status.PUBLISHED)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UiLayoutPublishHandler handler = new UiLayoutPublishHandler(repo, new LayoutValidator());

        String submittedSnapshot = "{\"portal\":\"admin\",\"menus\":[{\"id\":\"m1\",\"label\":\"Approved\"," +
            "\"screens\":[{\"id\":\"s1\",\"label\":\"Dash\",\"placedWidgets\":[]}]}]}";
        String laterUnreviewedEdit = "{\"portal\":\"admin\",\"menus\":[]}";
        draft.layoutJson = laterUnreviewedEdit; // maker snuck in an edit after submit
        ApprovalRequest approval = new ApprovalRequest();
        approval.id = UUID.randomUUID();
        approval.targetResourceId = draft.id;
        approval.payload = submittedSnapshot; // what the checker actually reviewed
        handler.apply(approval, true, CHECKER, "ok");

        // The PUBLISHED row carries the approved snapshot, never the later unreviewed edit.
        org.mockito.Mockito.verify(repo).save(org.mockito.Mockito.argThat(l ->
            UiLayout.Status.PUBLISHED.equals(l.status)
                && l.layoutJson.equals(submittedSnapshot)
                && !l.layoutJson.equals(laterUnreviewedEdit)
                && l.publishedApprovalId.equals(approval.id)));
    }

    @Test
    void handlerRefusesToPublishWithoutSubmittedSnapshot() {
        UiLayout draft = draft();
        UiLayoutRepository repo = org.mockito.Mockito.mock(UiLayoutRepository.class);
        when(repo.findById(draft.id)).thenReturn(Optional.of(draft));
        UiLayoutPublishHandler handler = new UiLayoutPublishHandler(repo, new LayoutValidator());

        ApprovalRequest approval = new ApprovalRequest();
        approval.id = UUID.randomUUID();
        approval.targetResourceId = draft.id;
        approval.payload = null;
        assertThatThrownBy(() -> handler.apply(approval, true, CHECKER, "ok"))
            .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void publishIsAtomicWithApprovalResolution() {
        // DB-only effect (the layout row) must commit WITH the approval row — else a
        // bundle failure strands an APPROVED approval whose layout never publishes.
        assertThat(new UiLayoutPublishHandler(null, new LayoutValidator()).atomicWithResolution())
            .isTrue();
    }

    @Test
    void publishHandlerOpTypeIsWired() {
        assertThat(new UiLayoutPublishHandler(null, new LayoutValidator()).operationType())
            .isEqualTo(ApprovalRequest.OpType.UI_LAYOUT_PUBLISH);
    }

    private static UiLayout draft() {
        UiLayout draft = new UiLayout();
        draft.id = UUID.fromString("40000000-0000-0000-0000-000000000401");
        draft.portal = "admin";
        draft.status = UiLayout.Status.DRAFT;
        draft.layoutJson = "{\"portal\":\"admin\",\"menus\":[]}";
        draft.schemaVersion = 1;
        draft.makerId = ADMIN.personId();
        return draft;
    }
}
