package com.fsp.core.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsp.core.approval.ApprovalService;
import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.config.FspProperties;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.rbac.PermissionService;
import com.fsp.core.rbac.RbacRepository;
import com.fsp.core.rbac.RoleRepository;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Gap probe — the users page names the organization it lists (own org, or the org being acted
 * as), so the SPA needs no tenant-detail read — which demands permissions a user.manage-only
 * administrator may not hold.
 */
class UserAdminServiceOrgRefTest {

    private static final UUID ROOT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private TenantRepository tenants;
    private PersonRepository persons;
    private PermissionService permissions;
    private UserAdminService svc;

    @BeforeEach
    void setUp() {
        tenants = mock(TenantRepository.class);
        persons = mock(PersonRepository.class);
        permissions = mock(PermissionService.class);
        RoleRepository roles = mock(RoleRepository.class);
        RbacRepository rbac = mock(RbacRepository.class);

        Tenant root = tenant(ROOT, "/" + ROOT, 1, "PSP", "Acme PSP");
        lenient().when(tenants.existsById(any())).thenReturn(true);
        lenient().when(tenants.findById(ROOT)).thenReturn(Optional.of(root));
        lenient().doNothing().when(permissions).require(any(Actor.class), anyString(), eq(ROOT));
        lenient().when(persons.countByStatus(any())).thenReturn(List.of());
        lenient().when(roles.findByTenantId(any())).thenReturn(List.of());

        svc = new UserAdminService(persons, mock(PersonProvisioning.class), roles, rbac, tenants, permissions,
            mock(AuditService.class), mock(OutboxService.class), mock(ApprovalService.class),
            new ObjectMapper(), new FspProperties());
    }

    /** The users page names the org it lists — no tenant-detail call (which needs other permissions). */
    @Test
    @SuppressWarnings("unchecked")
    void usersPageCarriesTheOrgItLists() {
        when(persons.findAll(any(Specification.class), any(Pageable.class)))
            .thenAnswer(inv -> new PageImpl<Person>(List.of(), inv.getArgument(1), 0));
        UserDtos.UserPage page = svc.list(ROOT, admin(), 0, 20, null, null, null);
        assertThat(page.org().id()).isEqualTo(ROOT);
        assertThat(page.org().legalName()).isEqualTo("Acme PSP");
        assertThat(page.org().type()).isEqualTo("PSP");
    }

    private static Tenant tenant(UUID id, String path, int depth, String type, String name) {
        Tenant t = new Tenant();
        t.id = id;
        t.hierarchyPath = path;
        t.depth = depth;
        t.type = type;
        t.status = "ACTIVE";
        t.verificationStatus = "VERIFIED";
        t.legalName = name;
        t.createdAt = Instant.now();
        return t;
    }

    private static Actor admin() {
        return new Actor(ADMIN, null, ROOT);
    }
}
