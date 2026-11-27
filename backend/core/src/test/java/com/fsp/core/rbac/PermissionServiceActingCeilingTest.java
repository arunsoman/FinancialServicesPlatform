package com.fsp.core.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fsp.core.common.Actor;
import com.fsp.core.common.RequestCorrelation;
import com.fsp.core.identity.Person;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Unit tests for the act-as permission ceiling (PermissionService.effectiveKeysForActing):
 * while acting, effective keys = (holds at target ∪ holds at home) ∩ ceiling, where
 * ceiling = permissions of roles held DIRECTLY at the target tenant. Acting can only
 * narrow what the actor may do — never escalate (plans/act-as-tenant-plan.md §3.4).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionServiceActingCeilingTest {

    private static final UUID PLATFORM = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PSP = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID TARGET = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID OTHER_ORG = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final UUID PERSON = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROLE_PLATFORM_ADMIN = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROLE_TENANT_OPS = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ROLE_OTHER_ORG = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock RbacRepository rbacRepository;
    @Mock RoleRepository roleRepository;
    @Mock TenantRepository tenantRepository;
    @Mock PermissionRepository permissionRepository;
    @Mock com.fsp.core.identity.PersonRepository personRepository;
    @Mock JdbcTemplate jdbc;

    PermissionService service;

    @BeforeEach
    void setUp() {
        service = new PermissionService(rbacRepository, roleRepository, tenantRepository,
            permissionRepository, personRepository, jdbc);

        // hierarchy: PLATFORM (depth 0) → PSP (1) → TARGET (2), plus an unrelated sibling org
        lenient().when(tenantRepository.findById(PLATFORM)).thenReturn(Optional.of(
            tenant(PLATFORM, null, "/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")));
        lenient().when(tenantRepository.findById(TARGET)).thenReturn(Optional.of(
            tenant(TARGET, PSP,
                "/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/"
                    + "cccccccc-cccc-cccc-cccc-cccccccccccc")));

        Person active = new Person();
        active.id = PERSON;
        active.status = "ACTIVE";
        lenient().when(personRepository.findById(PERSON)).thenReturn(Optional.of(active));

        // Actor holds exactly one role: PLATFORM_ADMIN at the platform root
        UserRole assignment = new UserRole();
        assignment.personId = PERSON;
        assignment.roleId = ROLE_PLATFORM_ADMIN;
        lenient().when(rbacRepository.findByPersonId(PERSON)).thenReturn(List.of(assignment));

        Role platformAdmin = role(ROLE_PLATFORM_ADMIN, PLATFORM, "PLATFORM_ADMIN", "PLATFORM");
        lenient().when(roleRepository.findById(ROLE_PLATFORM_ADMIN)).thenReturn(Optional.of(platformAdmin));
        lenient().when(rbacRepository.permissionKeysForPerson(PERSON))
            .thenReturn(List.of("tenant.act_as", "org.create", "je.create"));
    }

    private Tenant tenant(UUID id, UUID parent, String path) {
        Tenant t = new Tenant();
        t.id = id;
        t.parentTenantId = parent;
        t.hierarchyPath = path;
        t.status = "ACTIVE";
        return t;
    }

    private Role role(UUID id, UUID tenantId, String key, String scope) {
        Role r = new Role();
        r.id = id;
        r.tenantId = tenantId;
        r.key = key;
        r.name = key;
        r.scope = scope;
        return r;
    }

    private Actor platformActor() {
        return new Actor(PERSON, "ravi", PLATFORM, Actor.ActorType.PERSON, null);
    }

    @Test
    void actingIsCappedByRolesDirectlyAtTarget() {
        // Target org's own role allows only journal duties — the platform admin's
        // tenant.act_as and org.create are capped away while acting.
        Role tenantOps = role(ROLE_TENANT_OPS, TARGET, "TENANT_OPS", "TENANT");
        when(roleRepository.findByTenantId(TARGET)).thenReturn(List.of(tenantOps));
        when(rbacRepository.permissionKeysForRole(ROLE_TENANT_OPS))
            .thenReturn(List.of("je.create", "je.view"));
        when(roleRepository.findById(ROLE_TENANT_OPS)).thenReturn(Optional.of(tenantOps));

        Set<String> keys = service.effectiveKeysForActing(platformActor(), PLATFORM, TARGET);

        assertThat(keys).containsExactly("je.create");
    }

    @Test
    void actingYieldsNothingWhenTargetHasNoRolesOfItsOwn() {
        // No role is defined directly at the target — the ceiling is empty, so even the
        // platform-wide admin keys are capped away. Acting cannot create power out of nothing.
        when(roleRepository.findByTenantId(TARGET)).thenReturn(List.of());

        Set<String> keys = service.effectiveKeysForActing(platformActor(), PLATFORM, TARGET);

        assertThat(keys).isEmpty();
    }

    @Test
    void ceilingIgnoresEdgeScopeRoles() {
        // EDGE scope is reserved (ignored in Phase-0) — an EDGE role directly at the target
        // contributes nothing to the ceiling.
        Role edgeRole = role(ROLE_TENANT_OPS, TARGET, "EDGE_OPS", "EDGE");
        when(roleRepository.findByTenantId(TARGET)).thenReturn(List.of(edgeRole));
        when(rbacRepository.permissionKeysForRole(ROLE_TENANT_OPS)).thenReturn(List.of("tenant.act_as"));

        Set<String> keys = service.effectiveKeysForActing(platformActor(), PLATFORM, TARGET);

        assertThat(keys).isEmpty();
    }

    @Test
    void nonActivePersonHoldsNothingWhileActing() {
        Person disabled = new Person();
        disabled.id = PERSON;
        disabled.status = "DISABLED";
        when(personRepository.findById(PERSON)).thenReturn(Optional.of(disabled));
        when(roleRepository.findByTenantId(TARGET)).thenReturn(List.of());

        Set<String> keys = service.effectiveKeysForActing(platformActor(), PLATFORM, TARGET);

        assertThat(keys).isEmpty();
    }

    @Test
    void machineActorHoldsNothingWhileActing() {
        // A machine client has no person plane; four-eyes/acting stays person-anchored.
        Actor machine = new Actor(null, "kyc-plugin", TARGET, Actor.ActorType.MACHINE, "clid-1");
        when(roleRepository.findByTenantId(TARGET)).thenReturn(List.of());

        Set<String> keys = service.effectiveKeysForActing(machine, PLATFORM, TARGET);

        assertThat(keys).isEmpty();
    }

    /* ------------------------------------------------- effectiveKeys wiring (ActAsFilter path) */

    @Test
    void effectiveKeysCapsAnActingActorThatArrivedThroughActAs() {
        // ActAsFilter replaces the principal with ACTING + records the home tenant —
        // effectiveKeys must route that actor through the ceiling cap.
        Role tenantOps = role(ROLE_TENANT_OPS, TARGET, "TENANT_OPS", "TENANT");
        when(roleRepository.findByTenantId(TARGET)).thenReturn(List.of(tenantOps));
        when(rbacRepository.permissionKeysForRole(ROLE_TENANT_OPS))
            .thenReturn(List.of("je.create", "je.view"));
        Actor acting = new Actor(PERSON, "ravi", TARGET, Actor.ActorType.ACTING, null);
        try {
            RequestCorrelation.setHomeTenantId(PLATFORM);
            Set<String> keys = service.effectiveKeys(acting, TARGET);
            assertThat(keys).containsExactly("je.create");
        } finally {
            RequestCorrelation.clear();
        }
    }

    @Test
    void effectiveKeysDoesNotCapAPluginDelegatedActor() {
        // DELEGATED names a plugin acting FOR a person (clientId set) — not act-as, so the
        // ceiling does not apply and it resolves like the person.
        Actor pluginDelegated = new Actor(PERSON, "ravi", TARGET, Actor.ActorType.DELEGATED, "kyc-plugin");
        RequestCorrelation.clear();
        Set<String> keys = service.effectiveKeys(pluginDelegated, TARGET);
        assertThat(keys).containsExactlyInAnyOrder("tenant.act_as", "org.create", "je.create");
    }

    @Test
    void anActingActorWithoutAnActAsHomeHoldsNothing() {
        // ACTING only ever comes from ActAsFilter, which records the home tenant; without it the
        // actor is forged or mis-wired — fail closed instead of resolving as a plain person.
        Actor acting = new Actor(PERSON, "ravi", TARGET, Actor.ActorType.ACTING, null);
        RequestCorrelation.clear();
        assertThat(service.effectiveKeys(acting, TARGET)).isEmpty();
    }

    @Test
    void aPluginDelegatedActorWithAStrayHomeTenantIsNotTreatedAsActing() {
        // the pre-fix rule keyed act-as off (DELEGATED, clientId null, home set); a plugin actor
        // must never be routed through the act-as branch just because a home tenant is present.
        Actor pluginDelegated = new Actor(PERSON, "ravi", TARGET, Actor.ActorType.DELEGATED, "kyc-plugin");
        try {
            RequestCorrelation.setHomeTenantId(PLATFORM);
            assertThat(service.effectiveKeys(pluginDelegated, TARGET))
                .containsExactlyInAnyOrder("tenant.act_as", "org.create", "je.create");
        } finally {
            RequestCorrelation.clear();
        }
    }

    @Test
    void aPluginActingForAnActingPersonIsCappedByTheTargetCeiling() {
        // act-as through a plugin: core verified the session on the on-behalf path and recorded
        // home + session — the person side of the DELEGATED actor is capped like ACTING
        Role tenantOps = role(ROLE_TENANT_OPS, TARGET, "TENANT_OPS", "TENANT");
        when(roleRepository.findByTenantId(TARGET)).thenReturn(List.of(tenantOps));
        when(rbacRepository.permissionKeysForRole(ROLE_TENANT_OPS)).thenReturn(List.of("je.create", "je.view"));
        Actor plugin = new Actor(PERSON, "ravi", TARGET, Actor.ActorType.DELEGATED, "plugin-kyc");
        try {
            RequestCorrelation.setHomeTenantId(PLATFORM);
            RequestCorrelation.setActSessionId("sess-1");
            assertThat(service.effectiveKeys(plugin, TARGET)).containsExactly("je.create");
        } finally {
            RequestCorrelation.clear();
        }
    }
}
