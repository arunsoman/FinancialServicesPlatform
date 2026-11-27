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
import com.fsp.core.rbac.RoleRepository;
import com.fsp.core.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gap probe — users list paging (user-management Phase A).
 *
 * <p>Before the fix {@code GET /tenants/{id}/users} returned a bare {@code List} capped at 25 rows
 * with no paging metadata, and the SPA filtered/searched that first page in the browser: any
 * user past row 25 was invisible, and the status counts and search were wrong. The list must be
 * a page envelope (AGENTS §2f) with total + per-status counts, a sort safelist, and the search /
 * status filter pushed to the database as a {@link Specification}.
 */
class UserAdminServiceListTest {

    private static final UUID TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private PersonRepository persons;
    private UserAdminService svc;

    @BeforeEach
    void setUp() {
        persons = mock(PersonRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        RbacRepository rbac = mock(RbacRepository.class);
        TenantRepository tenants = mock(TenantRepository.class);
        PermissionService permissions = mock(PermissionService.class);

        lenient().when(tenants.existsById(TENANT)).thenReturn(true);
        com.fsp.core.tenant.Tenant org = new com.fsp.core.tenant.Tenant();
        org.id = TENANT;
        org.legalName = "Acme";
        org.type = "TENANT";
        lenient().when(tenants.findById(TENANT)).thenReturn(java.util.Optional.of(org));
        lenient().doNothing().when(permissions).require(any(Actor.class), anyString(), eq(TENANT));
        lenient().when(roles.findByTenantId(TENANT)).thenReturn(List.of());
        lenient().when(rbac.findByPersonIdIn(any())).thenReturn(List.of());
        lenient().when(persons.countByStatus(TENANT)).thenReturn(List.of(
            count("ACTIVE", 27), count("PENDING", 2), count("ARCHIVED", 1)));

        svc = new UserAdminService(persons, mock(PersonProvisioning.class), roles, rbac, tenants, permissions,
            mock(AuditService.class), mock(OutboxService.class), mock(ApprovalService.class),
            new ObjectMapper(), new FspProperties());
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Pageable> stubPage(int total) {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(persons.findAll(any(Specification.class), pageable.capture())).thenAnswer(inv -> {
            Pageable p = inv.getArgument(1);
            int from = (int) p.getOffset();
            int to = Math.min(total, from + p.getPageSize());
            List<Person> rows = IntStream.range(from, Math.max(from, to)).mapToObj(i -> person("user." + i)).toList();
            return new PageImpl<>(rows, p, total);
        });
        return pageable;
    }

    /** The 30-user org: page 1 (size 20) holds users 21–30 and the envelope reports the total. */
    @Test
    void secondPageIsReachableAndCarriesTotals() {
        ArgumentCaptor<Pageable> pageable = stubPage(30);

        UserDtos.UserPage page = svc.list(TENANT, admin(), 1, 20, null, null, null);

        assertThat(page.items()).hasSize(10);
        assertThat(page.items().getLast().username()).isEqualTo("user.29");
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(30);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.hasNext()).isFalse();
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
    }

    @Test
    void defaultsToStandardPageSizeAndCapsAt100() {
        ArgumentCaptor<Pageable> pageable = stubPage(5);
        svc.list(TENANT, admin(), null, null, null, null, null);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getPageNumber()).isZero();

        svc.list(TENANT, admin(), -3, 10_000, null, null, null);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        assertThat(pageable.getValue().getPageNumber()).isZero();
    }

    /** Status counts come from ONE grouped query over the whole org, not from the visible page. */
    @Test
    void countsCoverEveryStatusOfTheWholeOrg() {
        stubPage(30);
        UserDtos.UserPage page = svc.list(TENANT, admin(), 0, 20, null, null, null);
        assertThat(page.counts()).containsEntry("ACTIVE", 27L).containsEntry("PENDING", 2L)
            .containsEntry("DISABLED", 0L).containsEntry("ARCHIVED", 1L).containsEntry("CURRENT", 29L);
    }

    @Test
    void sortIsSafelistedAndStableById() {
        ArgumentCaptor<Pageable> pageable = stubPage(3);
        svc.list(TENANT, admin(), 0, 20, null, null, "fullName,desc");
        Sort sort = pageable.getValue().getSort();
        assertThat(sort.getOrderFor("fullName")).isNotNull();
        assertThat(sort.getOrderFor("fullName").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(sort.getOrderFor("id")).as("tie-break keeps pages stable").isNotNull();

        svc.list(TENANT, admin(), 0, 20, null, null, null);
        assertThat(pageable.getValue().getSort().getOrderFor("username").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sortOutsideTheSafelistIsRejected() {
        for (String bad : List.of("passwordHash,asc", "username,sideways", "tenant.id", ",asc")) {
            assertThatThrownBy(() -> svc.list(TENANT, admin(), 0, 20, null, null, bad))
                .as(bad).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("SORT_INVALID");
        }
        verify(persons, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void unknownStatusFilterIsRejected() {
        assertThatThrownBy(() -> svc.list(TENANT, admin(), 0, 20, null, "DELETED", null))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getCode()).isEqualTo("STATUS_INVALID");
    }

    @Test
    void acceptedStatusFiltersRunAgainstTheDatabase() {
        stubPage(1);
        for (String s : List.of("current", "CURRENT", "PENDING", "ACTIVE", "DISABLED", "ARCHIVED")) {
            assertThat(svc.list(TENANT, admin(), 0, 20, "anna", s, null).totalElements()).as(s).isEqualTo(1);
        }
    }

    /** User text is matched literally: LIKE wildcards in the search are escaped. */
    @Test
    void searchPatternEscapesLikeWildcards() {
        assertThat(UserAdminService.likePattern("  Anna ")).isEqualTo("%anna%");
        assertThat(UserAdminService.likePattern("50%_off\\")).isEqualTo("%50\\%\\_off\\\\%");
        assertThat(UserAdminService.likePattern("   ")).isNull();
        assertThat(UserAdminService.likePattern(null)).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private static PersonRepository.StatusCount count(String status, long n) {
        return new PersonRepository.StatusCount() {
            @Override public String getStatus() { return status; }
            @Override public long getN() { return n; }
        };
    }

    private static Person person(String username) {
        Person p = new Person();
        p.id = UUID.randomUUID();
        p.tenantId = TENANT;
        p.username = username;
        p.fullName = username;
        p.passwordHash = "";
        p.status = "ACTIVE";
        p.version = 1L;
        p.createdAt = Instant.now();
        p.updatedAt = Instant.now();
        return p;
    }

    private static Actor admin() {
        return new Actor(ADMIN, null, TENANT);
    }
}
