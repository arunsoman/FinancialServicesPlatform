package com.fsp.core.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.common.KeyLocks;
import com.fsp.core.identity.Person;
import com.fsp.core.identity.PersonRepository;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.plugin.registry.PluginTypeRepository;
import com.fsp.core.rbac.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** GAP-3: what the approvals queue tells each caller, and who may resolve what. */
class ApprovalServiceGovernanceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MAKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CHECKER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private ApprovalRequestRepository repo;
    private PermissionService permissions;
    private PersonRepository persons;
    private ApprovalService svc;

    @BeforeEach
    void setUp() {
        repo = mock(ApprovalRequestRepository.class);
        permissions = mock(PermissionService.class);
        persons = mock(PersonRepository.class);
        svc = new ApprovalService(repo, permissions, new KeyLocks(), mock(AuditService.class), mock(OutboxService.class),
            List.of(), persons, mock(PluginTypeRepository.class), mock(PlatformTransactionManager.class),
            mock(com.fsp.core.notify.NotificationService.class));
        when(persons.findById(MAKER_ID)).thenReturn(Optional.of(person(MAKER_ID, "maker", false)));
        when(persons.findById(CHECKER_ID)).thenReturn(Optional.of(person(CHECKER_ID, "checker", true)));
        when(permissions.effectiveKeys(any(), any())).thenReturn(Set.of("user.manage", "role.manage"));
    }

    @Test
    void makerSeesOwnRequestAsBlockedNotHidden() {
        ApprovalRequest row = row(ApprovalRequest.OpType.USER_CREATE);
        ApprovalDtos.QueueView v = svc.view(row, new Actor(MAKER_ID, "maker", TENANT));
        assertThat(v.canDecide()).isFalse();
        assertThat(v.blockedReason()).isEqualTo("OWN_REQUEST");
        assertThat(v.makerName()).isEqualTo("maker");
    }

    @Test
    void flaggedCheckerWithPermissionCanDecide() {
        ApprovalDtos.QueueView v = svc.view(row(ApprovalRequest.OpType.ROLE_UPDATE), new Actor(CHECKER_ID, "checker", TENANT));
        assertThat(v.canDecide()).isTrue();
        assertThat(v.checkerPermission()).isEqualTo("role.manage");
        assertThat(v.payload()).isEqualTo("{\"k\":1}");
    }

    @Test
    void unflaggedPersonIsToldTheyAreNotAChecker() {
        UUID other = UUID.randomUUID();
        when(persons.findById(other)).thenReturn(Optional.of(person(other, "other", false)));
        ApprovalDtos.QueueView v = svc.view(row(ApprovalRequest.OpType.USER_CREATE), new Actor(other, "other", TENANT));
        assertThat(v.blockedReason()).isEqualTo("NOT_CHECKER");
    }

    @Test
    void pluginRowsNeverExposePayloadAndArePluginOwned() {
        ApprovalRequest row = row("PLUGIN:kyc:CASE_DECISION");
        ApprovalDtos.QueueView v = svc.view(row, new Actor(CHECKER_ID, "checker", TENANT));
        assertThat(v.payload()).isNull();
        assertThat(v.blockedReason()).isEqualTo("PLUGIN_OWNED");
    }

    @Test
    void personCannotResolveAPluginOwnedRowDirectly() {
        ApprovalRequest row = row("PLUGIN:kyc:CASE_DECISION");
        when(repo.findById(row.id)).thenReturn(Optional.of(row));
        assertThatThrownBy(() -> svc.resolve(row.id, true, new Actor(CHECKER_ID, "checker", TENANT), "ok"))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("APPROVAL_PLUGIN_OWNED"));
        verify(repo, never()).save(any());
    }

    @Test
    void journalPostingNeverRunsInsideTheResolutionTransaction() {
        PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
        ApprovalHandler je = new ApprovalHandler() {
            @Override public String operationType() { return ApprovalRequest.OpType.JE_POSTING; }
            @Override public void apply(ApprovalRequest a, boolean ok, Actor c, String r) { }
        };
        ApprovalService withJe = new ApprovalService(repo, permissions, new KeyLocks(), mock(AuditService.class),
            mock(OutboxService.class), List.of(je), persons, mock(PluginTypeRepository.class), txm,
            mock(com.fsp.core.notify.NotificationService.class));
        ApprovalRequest row = row(ApprovalRequest.OpType.JE_POSTING);
        when(repo.findById(row.id)).thenReturn(Optional.of(row));
        when(permissions.effectiveKeys(any(), any())).thenReturn(Set.of("je.approve"));

        withJe.resolve(row.id, true, new Actor(CHECKER_ID, "checker", TENANT), "ok");

        // AGENTS §3.4: the JE plan must COMMIT before the TigerBeetle call — no enclosing transaction
        verify(txm, never()).getTransaction(any());
        assertThat(row.status).isEqualTo(ApprovalRequest.Status.APPROVED);
    }

    @Test
    void roleChangeResolvesAtomicallyWithItsEffect() {
        PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
        ApprovalHandler role = new ApprovalHandler() {
            @Override public String operationType() { return ApprovalRequest.OpType.ROLE_UPDATE; }
            @Override public void apply(ApprovalRequest a, boolean ok, Actor c, String r) { }
            @Override public boolean atomicWithResolution() { return true; }
        };
        ApprovalService withRole = new ApprovalService(repo, permissions, new KeyLocks(), mock(AuditService.class),
            mock(OutboxService.class), List.of(role), persons, mock(PluginTypeRepository.class), txm,
            mock(com.fsp.core.notify.NotificationService.class));
        ApprovalRequest row = row(ApprovalRequest.OpType.ROLE_UPDATE);
        when(repo.findById(row.id)).thenReturn(Optional.of(row));

        withRole.resolve(row.id, true, new Actor(CHECKER_ID, "checker", TENANT), "ok");

        verify(txm).getTransaction(any());
    }

    /** V38: once anyone decides, no checker is still asked to act — every pointer is resolved. */
    @Test
    void decisionResolvesEveryCheckersNotification() {
        com.fsp.core.notify.NotificationService notifications = mock(com.fsp.core.notify.NotificationService.class);
        ApprovalService withNotify = new ApprovalService(repo, permissions, new KeyLocks(), mock(AuditService.class),
            mock(OutboxService.class), List.of(), persons, mock(PluginTypeRepository.class),
            mock(PlatformTransactionManager.class), notifications);
        ApprovalRequest row = row(ApprovalRequest.OpType.USER_UPDATE);
        when(repo.findById(row.id)).thenReturn(Optional.of(row));

        withNotify.resolve(row.id, false, new Actor(CHECKER_ID, "checker", TENANT), "not now");

        verify(notifications).resolveApproval(row.id);
    }

    /** The bell opens the approval it points at — same checker gate as the queue, in the ROW's tenant. */
    @Test
    void singleApprovalReadUsesTheQueueGateInTheRowsTenant() {
        ApprovalRequest row = row(ApprovalRequest.OpType.USER_UPDATE);
        when(repo.findById(row.id)).thenReturn(Optional.of(row));
        Actor checker = new Actor(CHECKER_ID, "checker", TENANT);

        ApprovalDtos.QueueView v = svc.viewById(row.id, checker);

        verify(permissions).requireAny(checker, ApprovalService.CHECKER_PERMISSIONS, TENANT);
        assertThat(v.id()).isEqualTo(row.id);
        assertThat(v.canDecide()).isTrue();
    }

    /**
     * Every core op type a domain flow opens must survive canonicalisation — otherwise it is
     * stored as GENERIC, its handler never runs, and the approval is a silent no-op (an admin
     * password reset was approved and no reset link was ever sent).
     */
    @Test
    void everyCoreOperationTypeIsStoredAsItself() {
        for (String op : List.of(
                ApprovalRequest.OpType.USER_CREATE, ApprovalRequest.OpType.USER_UPDATE,
                ApprovalRequest.OpType.USER_DEACTIVATE, ApprovalRequest.OpType.USER_ACTIVATE,
                ApprovalRequest.OpType.USER_REMOVE, ApprovalRequest.OpType.USER_PASSWORD_RESET,
                ApprovalRequest.OpType.ROLE_CREATE, ApprovalRequest.OpType.ROLE_UPDATE,
                ApprovalRequest.OpType.ROLE_DELETE, ApprovalRequest.OpType.JE_POSTING,
                ApprovalRequest.OpType.BANK_ACCOUNT_CREATE, ApprovalRequest.OpType.KYC_TENANT_ACTIVATION,
                ApprovalRequest.OpType.PLUGIN_REGISTER, ApprovalRequest.OpType.PLUGIN_INSTALL,
                ApprovalRequest.OpType.UI_LAYOUT_PUBLISH)) {
            ApprovalRequest opened = svc.open(TENANT, op, UUID.randomUUID(),
                new Actor(MAKER_ID, "maker", TENANT), "test", null);
            assertThat(opened.operationType).as(op).isEqualTo(op);
        }
    }

    /** A second identical request while one is pending is a clear 409, never a 500 from the unique index. */
    @Test
    void secondPendingRequestForTheSameTargetIsAClearConflict() {
        UUID target = UUID.randomUUID();
        when(repo.existsByOperationTypeAndTargetResourceIdAndStatus(
            ApprovalRequest.OpType.USER_PASSWORD_RESET, target, ApprovalRequest.Status.PENDING_CHECKER)).thenReturn(true);

        assertThatThrownBy(() -> svc.open(TENANT, ApprovalRequest.OpType.USER_PASSWORD_RESET, target,
                new Actor(MAKER_ID, "maker", TENANT), "again", null))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("APPROVAL_ALREADY_PENDING"));
        verify(repo, never()).save(any());
    }

    @Test
    void queueOpensForAnyCheckerPermission() {
        assertThat(ApprovalService.CHECKER_PERMISSIONS)
            .contains("user.manage", "role.manage", "plugin.manage", "kyc.approve", "je.approve");
    }

    private static ApprovalRequest row(String type) {
        ApprovalRequest r = new ApprovalRequest();
        r.id = UUID.randomUUID();
        r.tenantId = TENANT;
        r.operationType = type;
        r.targetResourceId = UUID.randomUUID();
        r.makerId = MAKER_ID;
        r.payload = "{\"k\":1}";
        r.payloadHash = "h";
        return r;
    }

    private static Person person(UUID id, String username, boolean checker) {
        Person p = new Person();
        p.id = id;
        p.username = username;
        p.isChecker = checker;
        p.status = "ACTIVE";
        return p;
    }
}
