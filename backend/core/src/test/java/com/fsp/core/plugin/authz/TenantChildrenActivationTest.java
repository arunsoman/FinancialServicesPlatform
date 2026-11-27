package com.fsp.core.plugin.authz;

import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.config.FspProperties;
import com.fsp.core.identity.Person;
import com.fsp.core.identity.PersonProvisioning;
import com.fsp.core.ledger.LedgerService;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.Role;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V36: the administrator and checker an organization is born with activate by a one-time
 * link/code on their primary contact — never with the shared bootstrap password (pre-fix
 * both were created ACTIVE with FSP_PERSON_PASSWORD and no contact at all).
 */
class TenantChildrenActivationTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final PersonProvisioning provisioning = mock(PersonProvisioning.class);
    private final TenantChildrenService service = new TenantChildrenService(tenants, provisioning,
        mock(AuditService.class), mock(OutboxService.class), mock(FspProperties.class), mock(LedgerService.class));
    private final Actor maker = new Actor(UUID.randomUUID(), "platform-admin", UUID.randomUUID());

    private UUID platform() {
        Tenant t = new Tenant();
        t.id = UUID.randomUUID();
        t.type = "PLATFORM";
        t.status = "ACTIVE";
        t.depth = 0;
        t.hierarchyPath = "/" + t.id;
        when(tenants.findById(t.id)).thenReturn(Optional.of(t));
        return t.id;
    }

    @Test
    void withoutContactsNoOrganizationIsCreated() {
        UUID root = platform();
        assertThatThrownBy(() -> service.createChild(root, "Acme", null, "acme.admin", null, null, null, maker))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CONTACT_REQUIRED"));
        verify(tenants, never()).save(any());
    }

    @Test
    void adminAndCheckerAreInvitedOnTheirPrimaryContactNeverGivenTheBootstrapPassword() {
        UUID root = platform();
        Role role = new Role();
        role.id = UUID.randomUUID();
        when(provisioning.ensureRole(any(), anyString(), anyString(), anyString(), anyList())).thenReturn(role);
        when(provisioning.createInvitedPerson(any(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyBoolean()))
            .thenAnswer(inv -> {
                Person p = new Person();
                p.id = UUID.randomUUID();
                return p;
            });

        var created = service.createChild(root, "Acme", null, "acme.admin", null,
            new TenantChildrenService.Invitee("Ada Admin", "ada@acme.example", null, "EMAIL"),
            new TenantChildrenService.Invitee("Carl Checker", null, "+264811112222", "PHONE"), maker);

        assertThat(created.invitedPersonIds()).hasSize(2);
        verify(provisioning).createInvitedPerson(any(), eq("acme.admin"), eq("Ada Admin"), eq("ada@acme.example"),
            any(), eq("EMAIL"), eq("ACTIVE"), eq(true), eq(false));
        verify(provisioning).createInvitedPerson(any(), eq("checker-acme.admin"), eq("Carl Checker"), any(),
            eq("+264811112222"), eq("PHONE"), eq("ACTIVE"), eq(true), eq(false));
        verify(provisioning, never()).createPerson(any(), anyString(), anyString(), any(), anyString(), anyBoolean(), anyBoolean());
    }
}
