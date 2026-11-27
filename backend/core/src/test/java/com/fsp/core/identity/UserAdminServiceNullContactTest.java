package com.fsp.core.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.approval.ApprovalService;
import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.config.FspProperties;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.PermissionService;
import com.fsp.core.rbac.RbacRepository;
import com.fsp.core.rbac.Role;
import com.fsp.core.rbac.RoleRepository;
import com.fsp.core.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * A user with no phone (or no email) must still be editable. The USER_UPDATE payload carries
 * the full proposed record, so absent contacts are JSON nulls: staging must not blow up on them
 * (Map.of rejects nulls → "Failed to serialize update payload"), and applying must keep them
 * null rather than turning them into the literal string "null".
 */
class UserAdminServiceNullContactTest {

    private static final UUID TENANT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final UUID CHECKER = UUID.fromString("cccccccc-0000-0000-0000-000000000002");
    private static final UUID TARGET = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    private final ObjectMapper mapper = new ObjectMapper();
    private ApprovalService approvals;
    private PersonProvisioning provisioning;
    private UserAdminService svc;
    private Person target;
    private Role opsRole;

    @BeforeEach
    void setUp() {
        PersonRepository persons = mock(PersonRepository.class);
        provisioning = mock(PersonProvisioning.class);
        RoleRepository roles = mock(RoleRepository.class);
        RbacRepository rbac = mock(RbacRepository.class);
        TenantRepository tenants = mock(TenantRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        approvals = mock(ApprovalService.class);

        opsRole = new Role();
        opsRole.id = UUID.randomUUID();
        opsRole.tenantId = TENANT;
        opsRole.key = "PLATFORM_ORG_ROLE";
        opsRole.name = "platform_org_role";
        opsRole.scope = "TENANT";
        opsRole.version = 1L;

        target = new Person();
        target.id = TARGET;
        target.tenantId = TENANT;
        target.username = "arun.email.test";
        target.fullName = "Arun Email Test";
        target.email = "arun@example.na";
        target.phone = null; // email-only user: the case that failed
        target.primaryContact = "EMAIL";
        target.passwordHash = "";
        target.status = "ACTIVE";
        target.version = 2L;
        target.createdAt = Instant.now();
        target.updatedAt = Instant.now();

        lenient().when(tenants.existsById(TENANT)).thenReturn(true);
        lenient().when(roles.findByTenantId(TENANT)).thenReturn(List.of(opsRole));
        lenient().when(roles.findById(opsRole.id)).thenReturn(Optional.of(opsRole));
        lenient().when(rbac.permissionKeysForRole(opsRole.id)).thenReturn(List.of("kyc.review", "org.create"));
        lenient().when(rbac.findByPersonId(TARGET)).thenReturn(List.of());
        lenient().when(rbac.findByPersonIdIn(any())).thenReturn(List.of());
        lenient().doNothing().when(permissions).require(any(Actor.class), anyString(), eq(TENANT));
        lenient().when(permissions.effectiveKeys(any(Actor.class), eq(TENANT)))
            .thenReturn(Set.of("user.manage", "kyc.review", "org.create"));
        lenient().when(persons.findById(TARGET)).thenReturn(Optional.of(target));
        lenient().when(persons.saveAndFlush(any(Person.class))).thenAnswer(inv -> inv.getArgument(0));

        svc = new UserAdminService(persons, provisioning, roles, rbac, tenants, permissions,
            mock(AuditService.class), mock(OutboxService.class), approvals, mapper, new FspProperties());
    }

    @Test
    void assigningRoleToUserWithoutPhoneStagesUpdateWithNullPhone() throws Exception {
        svc.update(TENANT, TARGET, new UserDtos.UpdateUser(
            null, null, null, List.of(opsRole.id), null, 2L), new Actor(ADMIN, null, TENANT));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(approvals).open(eq(TENANT), eq(ApprovalRequest.OpType.USER_UPDATE), eq(TARGET),
            any(Actor.class), anyString(), payload.capture());
        Map<?, ?> staged = mapper.readValue(payload.getValue(), Map.class);
        assertThat(staged.containsKey("phone")).isTrue();
        assertThat(staged.get("phone")).isNull();
        assertThat(staged.get("roleIds")).isEqualTo(List.of(opsRole.id.toString()));
    }

    @Test
    void applyingUpdateWithNullPhoneKeepsPhoneNullAndGrantsRole() {
        String payload = "{\"fullName\":\"Arun Email Test\",\"email\":\"arun@example.na\",\"phone\":null,"
            + "\"roleIds\":[\"" + opsRole.id + "\"],\"checker\":false}";

        svc.applyUpdate(TARGET, payload, CHECKER, "ok");

        assertThat(target.phone).isNull();
        assertThat(target.email).isEqualTo("arun@example.na");
        verify(provisioning).assignRole(TARGET, opsRole.id);
    }
}
