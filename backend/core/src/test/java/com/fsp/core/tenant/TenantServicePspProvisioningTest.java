package com.fsp.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fsp.core.common.Actor;
import com.fsp.core.config.FspProperties;
import com.fsp.core.identity.Person;
import com.fsp.core.identity.PersonProvisioning;
import com.fsp.core.identity.PersonRepository;
import com.fsp.core.rbac.PermissionService;
import com.fsp.core.rbac.Role;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** GAP-1 / GAP-5 / GAP-9: what the platform's "create PSP" actually provisions. */
class TenantServicePspProvisioningTest {

    private static final UUID ROOT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @SuppressWarnings("unchecked")
    void pspIsAManufacturerWithTemplateRolesAndADesignatedChecker() {
        TenantRepository tenants = mock(TenantRepository.class);
        PersonProvisioning provisioning = mock(PersonProvisioning.class);
        FspProperties props = new FspProperties();
        TenantService svc = new TenantService(tenants, mock(PersonRepository.class), provisioning,
            mock(PermissionService.class), props);

        Tenant root = new Tenant();
        root.id = ROOT;
        root.type = "PLATFORM";
        root.depth = 0;
        root.hierarchyPath = "/" + ROOT;
        when(tenants.findById(ROOT)).thenReturn(Optional.of(root));
        when(provisioning.ensureRole(any(), anyString(), anyString(), anyString(), anyList())).thenAnswer(i -> {
            Role r = new Role();
            r.id = UUID.randomUUID();
            r.key = i.getArgument(1);
            return r;
        });
        when(provisioning.createPerson(any(), anyString(), anyString(), isNull(), any(), anyBoolean(), anyBoolean())).thenAnswer(i -> {
            Person p = new Person();
            p.id = UUID.randomUUID();
            p.username = i.getArgument(1);
            return p;
        });

        svc.createPsp(new Actor(UUID.randomUUID(), "platform-admin", ROOT),
            new TenantDtos.CreatePspRequest("Acme (Pty) Ltd", "acme.admin", "Acme Admin", "acme.checker", "Acme Checker"));

        ArgumentCaptor<Tenant> saved = ArgumentCaptor.forClass(Tenant.class);
        verify(tenants).save(saved.capture());
        assertThat(saved.getValue().subType).isEqualTo("MANUFACTURER");           // GAP-5

        ArgumentCaptor<List<String>> adminKeys = ArgumentCaptor.forClass(List.class);
        verify(provisioning).ensureRole(any(), eq("TENANT_ADMIN"), anyString(), anyString(), adminKeys.capture());
        assertThat(adminKeys.getValue()).contains("org.onboard_child", "user.manage", "role.manage"); // GAP-1

        ArgumentCaptor<Person> designated = ArgumentCaptor.forClass(Person.class);
        verify(provisioning).designateChecker(designated.capture());              // GAP-9
        assertThat(designated.getValue().username).isEqualTo("acme.checker");
    }
}
