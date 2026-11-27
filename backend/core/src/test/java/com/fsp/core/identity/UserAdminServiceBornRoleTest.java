package com.fsp.core.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.core.approval.ApprovalService;
import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.config.FspProperties;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.PermissionService;
import com.fsp.core.rbac.RbacRepository;
import com.fsp.core.rbac.Role;
import com.fsp.core.rbac.RoleRepository;
import com.fsp.core.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Born-role carve-out in {@link UserAdminService#requireAssignable}: a maker holding
 * {@code user.manage} may stage the organization's own TENANT_ADMIN / TENANT_CHECKER for a
 * person OTHER than themselves (the merchant admin stages its checker — onboarding script
 * step 7.2), while self-assignment of a role whose keys they do not hold stays denied.
 */
class UserAdminServiceBornRoleTest {

    private static final UUID TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID SELF_ID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001");

    private PersonProvisioning provisioning;
    private UserAdminService svc;
    private Role checkerRole;
    private Role opsRole;

    @BeforeEach
    void setUp() {
        PersonRepository persons = mock(PersonRepository.class);
        provisioning = mock(PersonProvisioning.class);
        RoleRepository roles = mock(RoleRepository.class);
        RbacRepository rbac = mock(RbacRepository.class);
        TenantRepository tenants = mock(TenantRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        FspProperties props = new FspProperties();

        checkerRole = role(UUID.randomUUID(), "TENANT_CHECKER", "Tenant Checker");
        opsRole = role(UUID.randomUUID(), "OPS_ADMIN", "Ops Admin");

        lenient().when(tenants.existsById(TENANT)).thenReturn(true);
        lenient().when(roles.findByTenantId(TENANT)).thenReturn(List.of(checkerRole, opsRole));
        lenient().when(rbac.permissionKeysForRole(checkerRole.id)).thenReturn(List.of("je.approve", "kyc.approve"));
        lenient().when(rbac.permissionKeysForRole(opsRole.id)).thenReturn(List.of("je.create", "kyc.approve"));
        lenient().doNothing().when(permissions).require(any(Actor.class), anyString(), eq(TENANT));
        // The maker holds admin-side keys only — NOT je.approve / je.create / kyc.approve.
        lenient().when(permissions.effectiveKeys(any(Actor.class), eq(TENANT)))
            .thenReturn(Set.of("user.manage", "role.view", "role.manage", "audit.view"));
        lenient().when(persons.findByUsernameIgnoreCase(anyString())).thenReturn(Optional.empty());
        lenient().when(persons.findById(any(UUID.class)))
            .thenAnswer(inv -> ADMIN.equals(inv.getArgument(0))
                ? Optional.of(person(ADMIN, "admin.self"))
                : Optional.empty());
        lenient().when(provisioning.createInvitedPerson(eq(TENANT), anyString(), anyString(), any(), any(), any(),
                eq("PENDING"), eq(false), eq(false)))
            .thenAnswer(inv -> {
                Person p = new Person();
                p.id = UUID.randomUUID();
                p.tenantId = TENANT;
                p.username = inv.getArgument(1);
                p.fullName = inv.getArgument(2);
                p.passwordHash = "";
                p.isChecker = false;
                p.createdAt = Instant.now();
                p.updatedAt = Instant.now();
                return p;
            });
        lenient().when(persons.save(any(Person.class))).thenAnswer(inv -> inv.getArgument(0));

        svc = new UserAdminService(persons, provisioning, roles, rbac, tenants, permissions,
            mock(AuditService.class), mock(OutboxService.class), mock(ApprovalService.class),
            new ObjectMapper(), props);
    }

    private static Role role(UUID id, String key, String name) {
        Role r = new Role();
        r.id = id;
        r.tenantId = TENANT;
        r.key = key;
        r.name = name;
        r.scope = "TENANT";
        r.version = 1L;
        return r;
    }

    private static Person person(UUID id, String username) {
        Person p = new Person();
        p.id = id;
        p.tenantId = TENANT;
        p.username = username;
        p.fullName = username;
        p.passwordHash = "";
        p.isChecker = false;
        p.status = "ACTIVE";
        p.version = 1L;
        p.createdAt = Instant.now();
        p.updatedAt = Instant.now();
        return p;
    }

    private static Actor admin() {
        return new Actor(ADMIN, null, TENANT);
    }

    /** Merchant-admin stages the born TENANT_CHECKER role for a NEW person (script step 7.2): allowed. */
    @Test
    void adminMayStageBornCheckerRoleForAnotherPerson() {
        UserDtos.UserView view = svc.create(TENANT, new UserDtos.CreateUser(
            "tulonga.checker", "Tulonga Shipanga", "tulonga@example.na", null,
            List.of(checkerRole.id), true, "EMAIL"), admin());
        assertThat(view.username()).isEqualTo("tulonga.checker");
    }

    /** A custom role whose keys the admin does not hold is still denied (no-escalation intact). */
    @Test
    void nonBornRoleWithoutHeldKeysStaysDenied() {
        assertThatThrownBy(() -> svc.create(TENANT, new UserDtos.CreateUser(
            "temp.user", "Temp User", "temp@example.na", null,
            List.of(opsRole.id), false, "EMAIL"), admin()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ROLE_NOT_ASSIGNABLE"));
    }

    /** The same maker cannot hand the checker role to THEMSELVES (no self-escalation). */
    @Test
    void selfAssignmentStaysDenied() {
        assertThatThrownBy(() -> svc.update(TENANT, ADMIN, new UserDtos.UpdateUser(
            "Admin Self", null, null, List.of(checkerRole.id), null, 1L), admin()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ROLE_NOT_ASSIGNABLE"));
    }

    /** Sanity: Mockito strictness guard — the unused stub would otherwise fail the run. */
    @Test
    void fixturesWired() {
        assertThat(svc).isNotNull();
        assertThat(Mockito.mockingDetails(provisioning).getInvocations()).isEmpty();
    }
}
