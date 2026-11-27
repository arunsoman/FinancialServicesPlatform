package com.fsp.core.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.identity.Person;
import com.fsp.core.identity.PersonRepository;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import com.fsp.core.ui.UiLayout;
import com.fsp.core.ui.UiLayoutRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Menu-cell access: full-menu roles, union of grants, tenant-chain scoping, published-menu validation. */
class MenuAccessServiceTest {

    private static final UUID ROOT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORG = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_ORG = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PERSON = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Actor ACTOR = new Actor(PERSON, "teller", ORG);

    private RoleRepository roles;
    private RbacRepository rbac;
    private PersonRepository persons;
    private RoleMenuGrantRepository grants;
    private UiLayoutRepository layouts;
    private MenuAccessService svc;

    @BeforeEach
    void setUp() {
        TenantRepository tenants = mock(TenantRepository.class);
        roles = mock(RoleRepository.class);
        rbac = mock(RbacRepository.class);
        persons = mock(PersonRepository.class);
        grants = mock(RoleMenuGrantRepository.class);
        layouts = mock(UiLayoutRepository.class);
        svc = new MenuAccessService(tenants, roles, rbac, persons, grants, layouts, new ObjectMapper());

        when(tenants.findById(ROOT)).thenReturn(Optional.of(tenant(ROOT, "/" + ROOT, "PLATFORM", null)));
        when(tenants.findById(ORG)).thenReturn(Optional.of(tenant(ORG, "/" + ROOT + "/" + ORG, "TENANT", "MERCHANT")));
        Person p = new Person();
        p.id = PERSON;
        p.status = "ACTIVE";
        when(persons.findById(PERSON)).thenReturn(Optional.of(p));
        when(grants.cellsForRoles(anyCollection(), eq("merchant"))).thenReturn(Set.of("ops/till:ACTIVE", "ops/till:VIEW"));
    }

    @Test
    void customRoleHoldsExactlyItsGrantedCells() {
        assign(role(ORG, "TELLER", "TENANT"));
        MenuAccessService.MenuAccess a = svc.forActor(ACTOR, ORG, "merchant");
        assertThat(a.full()).isFalse();
        assertThat(a.cells()).containsExactlyInAnyOrder("ops/till:ACTIVE", "ops/till:VIEW");
        assertThat(a.holds("ops/till:DELETE")).isFalse();
    }

    @Test
    void anchorAndPlatformRolesHoldTheWholeMenu() {
        assign(role(ORG, RoleTemplates.ADMIN_ROLE, "TENANT"));
        assertThat(svc.forActor(ACTOR, ORG, "merchant").full()).isTrue();

        assign(role(ROOT, "PLATFORM_ADMIN", "PLATFORM"));
        assertThat(svc.forActor(ACTOR, ORG, "merchant").holds("anything/at-all:DELETE")).isTrue();
    }

    @Test
    void anotherOrganizationsRoleGrantsNothingHere() {
        assign(role(OTHER_ORG, RoleTemplates.ADMIN_ROLE, "TENANT"));
        MenuAccessService.MenuAccess a = svc.forActor(ACTOR, ORG, "merchant");
        assertThat(a.full()).isFalse();
        verify(grants).cellsForRoles(eq(Set.of()), eq("merchant"));
    }

    @Test
    void deactivatedPersonHoldsNothing() {
        assign(role(ORG, RoleTemplates.ADMIN_ROLE, "TENANT"));
        persons.findById(PERSON).orElseThrow().status = "DISABLED";
        MenuAccessService.MenuAccess a = svc.forActor(ACTOR, ORG, "merchant");
        assertThat(a.full()).isFalse();
        assertThat(a.cells()).isEmpty();
    }

    @Test
    void machinesHoldNoMenu() {
        Actor machine = new Actor(null, "kyc-plugin", ORG, Actor.ActorType.MACHINE, "kyc-plugin");
        assertThat(svc.forActor(machine, ORG, "merchant").cells()).isEmpty();
        verify(rbac, never()).findByPersonId(any());
    }

    @Test
    void cellsMustNameAPublishedSubmenu() {
        published("{\"menus\":[{\"id\":\"ops\",\"screens\":[{\"id\":\"till\"}]}]}");
        svc.requirePublished("merchant", List.of("ops/till:VIEW"));
        assertThatThrownBy(() -> svc.requirePublished("merchant", List.of("ops/vault:VIEW")))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("MENU_CELL_UNKNOWN"));
    }

    @Test
    void noPublishedMenuMeansNothingCanBeGranted() {
        when(layouts.findFirstByPortalAndStatus("merchant", UiLayout.Status.PUBLISHED)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.requirePublished("merchant", List.of("ops/till:VIEW")))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("MENU_NOT_PUBLISHED"));
    }

    @Test
    void portalFollowsTheOrganizationsTypeAndSubType() {
        assertThat(MenuAccessService.portalOf(tenant(ROOT, "/", "PLATFORM", null))).isEqualTo("admin");
        assertThat(MenuAccessService.portalOf(tenant(ORG, "/", "TENANT", "MERCHANT"))).isEqualTo("merchant");
        assertThat(MenuAccessService.portalOf(tenant(ORG, "/", "PSP", null))).isEqualTo("manufacturer");
        assertThat(MenuAccessService.portalOf(tenant(ORG, "/", "TENANT", "DEALER"))).isNull();
    }

    @Test
    void wireFormIsStrict() {
        assertThat(MenuCell.normalize(List.of("ops/till:VIEW", "ops/till:ACTIVE", "ops/till:VIEW")))
            .containsExactly("ops/till:ACTIVE", "ops/till:VIEW");
        for (String bad : List.of("ops/till", "Ops/till:VIEW", "ops/till:view", "ops/till:EXECUTE", "ops:till/VIEW")) {
            assertThatThrownBy(() -> MenuCell.parse(bad)).isInstanceOf(ApiException.class);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void published(String json) {
        UiLayout l = new UiLayout();
        l.id = UUID.randomUUID();
        l.portal = "merchant";
        l.status = UiLayout.Status.PUBLISHED;
        l.layoutJson = json;
        when(layouts.findFirstByPortalAndStatus("merchant", UiLayout.Status.PUBLISHED)).thenReturn(Optional.of(l));
    }

    private void assign(Role r) {
        UserRole ur = new UserRole();
        ur.personId = PERSON;
        ur.roleId = r.id;
        when(rbac.findByPersonId(PERSON)).thenReturn(List.of(ur));
        when(roles.findAllById(List.of(r.id))).thenReturn(List.of(r));
    }

    private static Role role(UUID tenant, String key, String scope) {
        Role r = new Role();
        r.id = UUID.randomUUID();
        r.tenantId = tenant;
        r.key = key;
        r.name = key;
        r.scope = scope;
        return r;
    }

    private static Tenant tenant(UUID id, String path, String type, String subType) {
        Tenant t = new Tenant();
        t.id = id;
        t.hierarchyPath = path;
        t.type = type;
        t.subType = subType;
        return t;
    }
}
