package com.fsp.core.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fsp.core.common.Actor;
import com.fsp.core.identity.Person;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;

/**
 * Red-team regression (rounds 1/3): tenant-tree escape via PermissionService.chainFor().
 * The actor's own tenant must NOT count as target-ancestor reachability — a role held in
 * tenant X must never grant permissions at an unrelated tenant Y (sibling tree), and the
 * cross-org user-admin takeover chain (user.manage at a foreign org) must stay closed.
 */
class PermissionServiceTenantEscapeTest {

    static final UUID PLATFORM = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID PSP_A1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID MERCHANT_A2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    static final UUID FOREIGN_ROOT = UUID.fromString("2712b509-3f29-4e8b-9a08-b6d34e8ffee7");
    static final UUID EVE = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    RbacRepository rbac; RoleRepository roles; TenantRepository tenants;
    PermissionRepository catalog; com.fsp.core.identity.PersonRepository persons;
    PermissionService svc;

    static Tenant tenant(UUID id, String path) {
        Tenant t = new Tenant(); t.id = id; t.hierarchyPath = path; t.status = "ACTIVE";
        return t;
    }

    @BeforeEach
    void setUp() {
        rbac = mock(RbacRepository.class); roles = mock(RoleRepository.class);
        tenants = mock(TenantRepository.class); catalog = mock(PermissionRepository.class);
        persons = mock(com.fsp.core.identity.PersonRepository.class);
        svc = new PermissionService(rbac, roles, tenants, catalog, persons, null);

        when(tenants.findById(PLATFORM)).thenReturn(Optional.of(tenant(PLATFORM, "/" + PLATFORM)));
        when(tenants.findById(PSP_A1)).thenReturn(Optional.of(tenant(PSP_A1, "/" + PLATFORM + "/" + PSP_A1)));
        when(tenants.findById(MERCHANT_A2))
            .thenReturn(Optional.of(tenant(MERCHANT_A2, "/" + PLATFORM + "/" + PSP_A1 + "/" + MERCHANT_A2)));
        // FOREIGN_ROOT: unrelated tree (platform → foreign root)
        when(tenants.findById(FOREIGN_ROOT)).thenReturn(Optional.of(tenant(FOREIGN_ROOT, "/" + PLATFORM + "/" + FOREIGN_ROOT)));

        Person active = new Person(); active.id = EVE; active.status = "ACTIVE";
        when(persons.findById(any())).thenReturn(Optional.of(active));
    }

    @Test
    void subTreeReachesDescendant_only() {
        // PSP admin role at a1 (SUBTREE): reaches a2 (descendant), NOT the foreign tree
        Role role = new Role(); role.id = UUID.randomUUID(); role.tenantId = PSP_A1; role.name = "PSP A"; role.scope = "SUBTREE";
        when(roles.findById(role.id)).thenReturn(Optional.of(role));
        com.fsp.core.rbac.UserRole ur = new com.fsp.core.rbac.UserRole();
        ur.personId = EVE; ur.roleId = role.id;
        when(rbac.findByPersonId(EVE)).thenReturn(List.of(ur));
        when(rbac.permissionKeysForPerson(EVE)).thenReturn(List.of("user.manage"));

        Actor eve = new Actor(EVE, "eve", PSP_A1);
        assertThat(svc.effectiveKeys(eve, MERCHANT_A2)).contains("user.manage");
        assertThat(svc.effectiveKeys(eve, FOREIGN_ROOT)).doesNotContain("user.manage");
        assertThat(svc.effectiveKeys(eve, PSP_A1)).contains("user.manage");
    }

    @Test
    void roleNeverReachesUnrelatedTree_evenWhenActorTenantDiffers() {
        // merchant role at a2 (TENANT scope): chain of foreign root must NOT include a2
        Role role = new Role(); role.id = UUID.randomUUID(); role.tenantId = MERCHANT_A2; role.name = "MO"; role.scope = "TENANT";
        when(roles.findById(role.id)).thenReturn(Optional.of(role));
        com.fsp.core.rbac.UserRole ur = new com.fsp.core.rbac.UserRole();
        ur.personId = EVE; ur.roleId = role.id;
        when(rbac.findByPersonId(EVE)).thenReturn(List.of(ur));
        when(rbac.permissionKeysForPerson(EVE)).thenReturn(List.of("je.create"));

        Actor eve = new Actor(EVE, "eve", MERCHANT_A2);
        assertThat(svc.effectiveKeys(eve, FOREIGN_ROOT)).isEmpty();
        assertThat(svc.effectiveKeys(eve, PSP_A1)).isEmpty();
        assertThat(svc.effectiveKeys(eve, MERCHANT_A2)).contains("je.create");
    }

    @Test
    void platformRootRoleReachesEveryOrg_onlyForPlatformHolders() {
        Role role = new Role(); role.id = UUID.randomUUID(); role.tenantId = PLATFORM; role.name = "PA"; role.scope = "PLATFORM";
        when(roles.findById(role.id)).thenReturn(Optional.of(role));
        com.fsp.core.rbac.UserRole ur = new com.fsp.core.rbac.UserRole();
        ur.personId = EVE; ur.roleId = role.id;
        when(rbac.findByPersonId(EVE)).thenReturn(List.of(ur));
        when(rbac.permissionKeysForPerson(EVE)).thenReturn(List.of("user.manage"));

        Actor eve = new Actor(EVE, "eve", PLATFORM);
        assertThat(svc.effectiveKeys(eve, FOREIGN_ROOT)).contains("user.manage");
        assertThat(svc.effectiveKeys(eve, PSP_A1)).contains("user.manage");
    }
}
