package com.fsp.core.tenant;

import com.fsp.core.common.Actor;
import com.fsp.core.config.FspProperties;
import com.fsp.core.identity.PersonProvisioning;
import com.fsp.core.identity.PersonRepository;
import com.fsp.core.rbac.PermissionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AGENTS §2.7: an org's children are a bounded server page — size clamped, sort allow-listed, search
 * pushed to the query. Pre-fix /children returned the whole unpaged list and the Onboarding panel
 * (which expects a page) always showed "0 onboarded".
 */
class TenantChildrenPageTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final TenantService service = new TenantService(tenants, mock(PersonRepository.class),
        mock(PersonProvisioning.class), mock(PermissionService.class), mock(FspProperties.class));
    private final UUID parent = UUID.randomUUID();
    private final Actor actor = new Actor(UUID.randomUUID(), "platform-admin", parent);

    private Tenant child(String name) {
        Tenant t = new Tenant();
        t.id = UUID.randomUUID();
        t.parentTenantId = parent;
        t.type = "PSP";
        t.subType = "MANUFACTURER";
        t.legalName = name;
        return t;
    }

    @Test
    void sizeIsClampedAndUnknownSortsFallBackToLegalName() {
        when(tenants.findByParentTenantId(eq(parent), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(child("Acme"))));

        TenantDtos.ChildPage page = service.childrenPage(parent, actor, -3, 10_000, null, "password,desc");

        ArgumentCaptor<Pageable> asked = ArgumentCaptor.forClass(Pageable.class);
        verify(tenants).findByParentTenantId(eq(parent), asked.capture());
        assertThat(asked.getValue().getPageNumber()).isZero();
        assertThat(asked.getValue().getPageSize()).isEqualTo(100);
        assertThat(asked.getValue().getSort().getOrderFor("legalName")).isNotNull();
        assertThat(asked.getValue().getSort().getOrderFor("password")).isNull();
        assertThat(page.items()).extracting(TenantDtos.TenantView::legalName).containsExactly("Acme");
        assertThat(page.items().get(0).childSubType()).isEqualTo("DISTRIBUTOR");
    }

    @Test
    void searchRunsInTheQueryAndSortDirectionIsHonoured() {
        when(tenants.findByParentTenantIdAndLegalNameContainingIgnoreCase(eq(parent), eq("acme"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        TenantDtos.ChildPage page = service.childrenPage(parent, actor, 1, 25, "  acme ", "createdAt,desc");

        ArgumentCaptor<Pageable> asked = ArgumentCaptor.forClass(Pageable.class);
        verify(tenants).findByParentTenantIdAndLegalNameContainingIgnoreCase(eq(parent), eq("acme"), asked.capture());
        assertThat(asked.getValue().getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(page.q()).isEqualTo("acme");
        assertThat(page.sort()).isEqualTo("createdAt,desc");
    }
}
