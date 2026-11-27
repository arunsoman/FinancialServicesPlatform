package com.fsp.core.orgunit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.rbac.PermissionService;
import com.fsp.core.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Branches / departments: tenant-scoped, server-paged + safelisted sort, archived/foreign units refused. */
class OrgUnitServiceTest {

    private static final UUID ORG = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Actor ACTOR = new Actor(UUID.randomUUID(), "maker", ORG);

    private BranchRepository branches;
    private PermissionService permissions;
    private OrgUnitService svc;

    @BeforeEach
    void setUp() {
        branches = mock(BranchRepository.class);
        permissions = mock(PermissionService.class);
        TenantRepository tenants = mock(TenantRepository.class);
        when(tenants.existsById(ORG)).thenReturn(true);
        svc = new OrgUnitService(branches, mock(DepartmentRepository.class), tenants, permissions);
    }

    @Test
    void listPushesPageSortAndSearchToTheDatabase() {
        when(branches.search(eq(ORG), eq("ACTIVE"), eq("head"), any())).thenReturn(new PageImpl<>(List.of(branch(ORG, "ACTIVE"))));
        OrgUnitDtos.OrgUnitPage page = svc.list(OrgUnitService.Kind.BRANCH, ORG, 0, 50, "name,desc", " Head ", "active", ACTOR);

        ArgumentCaptor<Pageable> pr = ArgumentCaptor.forClass(Pageable.class);
        verify(branches).search(eq(ORG), eq("ACTIVE"), eq("head"), pr.capture());
        assertThat(pr.getValue().getPageSize()).isEqualTo(50);
        assertThat(pr.getValue().getSort().getOrderFor("name").isDescending()).isTrue();
        assertThat(page.items()).hasSize(1);
        verify(permissions).requireAny(ACTOR, List.of("role.view", "role.manage"), ORG);
    }

    @Test
    void noSearchTextBindsAnEmptyStringNeverNull() {
        // PostgreSQL cannot type a null LIKE bind (text ~~ bytea → 500); "" matches every row
        when(branches.search(eq(ORG), eq(null), eq(""), any())).thenReturn(new PageImpl<>(List.of(branch(ORG, "ACTIVE"))));
        assertThat(svc.list(OrgUnitService.Kind.BRANCH, ORG, 0, 20, null, null, null, ACTOR).items()).hasSize(1);
        verify(branches).search(eq(ORG), eq(null), eq(""), any());
    }

    @Test
    void unsafeSortAndOversizedPagesAreRefused() {
        assertThatThrownBy(() -> svc.list(OrgUnitService.Kind.BRANCH, ORG, 0, 20, "tenantId,asc", null, null, ACTOR))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SORT_INVALID"));
        assertThatThrownBy(() -> svc.list(OrgUnitService.Kind.BRANCH, ORG, 0, 101, null, null, null, ACTOR))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("PAGE_INVALID"));
    }

    @Test
    void anotherOrganizationsBranchLooksMissing() {
        Branch foreign = branch(UUID.randomUUID(), "ACTIVE");
        when(branches.findById(foreign.id)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> svc.requireActive(OrgUnitService.Kind.BRANCH, ORG, foreign.id))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("BRANCH_UNKNOWN"));
    }

    @Test
    void archivedBranchCannotAnchorARole() {
        Branch old = branch(ORG, "ARCHIVED");
        when(branches.findById(old.id)).thenReturn(Optional.of(old));
        assertThatThrownBy(() -> svc.requireActive(OrgUnitService.Kind.BRANCH, ORG, old.id))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("BRANCH_ARCHIVED"));
    }

    private static Branch branch(UUID tenant, String status) {
        Branch b = new Branch();
        b.id = UUID.randomUUID();
        b.tenantId = tenant;
        b.code = "HO";
        b.name = "Head Office";
        b.status = status;
        return b;
    }
}
