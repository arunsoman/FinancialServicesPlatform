package com.fsp.core.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.core.approval.ApprovalRequest;
import com.fsp.core.approval.ApprovalService;
import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.config.FspProperties;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.PermissionService;
import com.fsp.core.rbac.RbacRepository;
import com.fsp.core.rbac.RoleRepository;
import com.fsp.core.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Users added from any portal's User management are IdP-managed: no password is ever set by
 * the admin; the person is invited (unusable secret, primary contact), the activation goes out
 * only after the four-eyes USER_CREATE approval, and a re-send is only for approved users who
 * haven't set their password yet.
 */
class UserInviteActivationTest {

    private static final UUID TENANT = UUID.randomUUID();
    private final PersonRepository persons = mock(PersonRepository.class);
    private final PersonProvisioning provisioning = mock(PersonProvisioning.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final UserAdminService svc = new UserAdminService(persons, provisioning, roles, mock(RbacRepository.class),
        tenants, mock(PermissionService.class), mock(AuditService.class), mock(OutboxService.class),
        mock(ApprovalService.class), new ObjectMapper(), new FspProperties());
    private final Actor admin = new Actor(UUID.randomUUID(), "admin", TENANT);

    UserInviteActivationTest() {
        when(tenants.existsById(TENANT)).thenReturn(true);
        when(roles.findByTenantId(TENANT)).thenReturn(List.of());
        when(persons.findByUsernameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(provisioning.createInvitedPerson(any(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyBoolean()))
            .thenAnswer(inv -> {
                Person p = new Person();
                p.id = UUID.randomUUID();
                p.tenantId = inv.getArgument(0);
                p.username = inv.getArgument(1);
                p.fullName = inv.getArgument(2);
                p.email = inv.getArgument(3);
                p.phone = inv.getArgument(4);
                p.primaryContact = inv.getArgument(5);
                p.status = inv.getArgument(6);
                p.createdAt = Instant.now();
                p.updatedAt = Instant.now();
                return p;
            });
    }

    private Person approved(String primary, Instant verifiedAt) {
        Person p = new Person();
        p.id = UUID.randomUUID();
        p.tenantId = TENANT;
        p.username = "ada";
        p.status = "ACTIVE";
        p.primaryContact = primary;
        p.contactVerifiedAt = verifiedAt;
        when(persons.findById(p.id)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void createInvitesWithTheContactAndNeverABootstrapPassword() {
        var view = svc.create(TENANT, new UserDtos.CreateUser("ada.admin", "Ada Admin", " ADA@x.com ", "0026481 111 2222",
            List.of(), false, "PHONE"), admin);

        verify(provisioning).createInvitedPerson(eq(TENANT), eq("ada.admin"), eq("Ada Admin"), eq("ada@x.com"),
            eq("+264811112222"), eq("PHONE"), eq("PENDING"), eq(false), eq(false));
        verify(provisioning, never()).createPerson(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
        assertThat(view.primaryContact()).isEqualTo("PHONE");
        assertThat(view.activated()).isFalse();
    }

    @Test
    void thePrimaryContactMustBeGiven() {
        assertThatThrownBy(() -> svc.create(TENANT, new UserDtos.CreateUser("ada.admin", "Ada Admin", null, null,
            List.of(), false, "EMAIL"), admin))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("CONTACT_REQUIRED"));
    }

    @Test
    void reSendIsOnlyForApprovedUsersWhoHaveNotActivated() {
        assertThat(svc.requireAwaitingActivation(TENANT, approved("EMAIL", null).id, admin)).isNotNull();
        assertThatThrownBy(() -> svc.requireAwaitingActivation(TENANT, approved("EMAIL", Instant.now()).id, admin))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ALREADY_ACTIVATED"));
        assertThatThrownBy(() -> svc.requireAwaitingActivation(TENANT, approved(null, null).id, admin))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("NO_ACTIVATION"));
        Person pending = approved("EMAIL", null);
        pending.status = "PENDING";
        assertThatThrownBy(() -> svc.requireAwaitingActivation(TENANT, pending.id, admin))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("USER_STATE"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void theActivationGoesOutOnlyWhenTheCheckerApproves() {
        UserAdminService users = mock(UserAdminService.class);
        IdpInternalClient idp = mock(IdpInternalClient.class);
        ObjectProvider<UserAdminService> u = mock(ObjectProvider.class);
        ObjectProvider<PersonRepository> pr = mock(ObjectProvider.class);
        ObjectProvider<IdpInternalClient> ip = mock(ObjectProvider.class);
        when(u.getObject()).thenReturn(users);
        when(pr.getObject()).thenReturn(persons);
        when(ip.getObject()).thenReturn(idp);
        var handler = new UserCreateApprovalHandler(u, pr, ip);
        Person invited = approved("EMAIL", null);
        Person legacy = approved(null, null);

        ApprovalRequest a = mock(ApprovalRequest.class);
        a.targetResourceId = invited.id;
        handler.apply(a, false, admin, "no");
        verify(idp, never()).triggerActivation(any());

        handler.apply(a, true, admin, "ok"); // no tx synchronization in a unit test → sent directly
        verify(idp).triggerActivation(invited.id);

        a.targetResourceId = legacy.id;
        handler.apply(a, true, admin, "ok");
        verify(idp, never()).triggerActivation(legacy.id);
    }

    @Test
    void aResetThatCannotBeDeliveredIsRefusedWithTheReason() {
        Person notActivated = approved("EMAIL", null);
        notActivated.email = "ada@x.com";
        assertThatThrownBy(() -> svc.passwordResetRequest(TENANT, notActivated.id, notActivated.version, admin))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ACTIVATION_PENDING"));
        Person noEmail = approved("PHONE", Instant.now());
        noEmail.phone = "+264811112222";
        assertThatThrownBy(() -> svc.passwordResetRequest(TENANT, noEmail.id, noEmail.version, admin))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("NO_EMAIL"));
    }
}
