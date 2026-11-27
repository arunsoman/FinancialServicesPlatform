package com.fsp.core.plugin.authz;

import com.fsp.core.audit.AuditService;
import com.fsp.core.common.Actor;
import com.fsp.core.common.ApiException;
import com.fsp.core.config.FspProperties;
import com.fsp.core.identity.PersonProvisioning;
import com.fsp.core.ledger.LedgerService;
import com.fsp.core.outbox.OutboxService;
import com.fsp.core.tenant.Tenant;
import com.fsp.core.tenant.TenantRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Core refuses off-ladder children (pre-fix any sub-type went under any parent, default MERCHANT):
 * a distributor cannot onboard a manufacturer, a merchant onboards nothing.
 */
class TenantChildrenLadderTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final TenantChildrenService service = new TenantChildrenService(tenants, mock(PersonProvisioning.class),
        mock(AuditService.class), mock(OutboxService.class), mock(FspProperties.class), mock(LedgerService.class));
    private final Actor maker = new Actor(UUID.randomUUID(), "platform-admin", UUID.randomUUID());

    private UUID parent(String type, String subType) {
        Tenant t = new Tenant();
        t.id = UUID.randomUUID();
        t.type = type;
        t.subType = subType;
        t.status = "ACTIVE";
        t.depth = 1;
        t.hierarchyPath = "/" + t.id;
        when(tenants.findById(t.id)).thenReturn(Optional.of(t));
        return t.id;
    }

    @Test
    void aDistributorCannotOnboardAManufacturer() {
        UUID dist = parent("TENANT", "DISTRIBUTOR");
        assertThatThrownBy(() -> service.createChild(dist, "Acme", "MANUFACTURER", "acme.admin", null, null, null, maker))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((ApiException) e).getCode()).isEqualTo("CHILD_SUBTYPE_NOT_ALLOWED"));
        verify(tenants, never()).save(any());
    }

    @Test
    void aMerchantOnboardsNothing() {
        UUID merchant = parent("TENANT", "MERCHANT");
        assertThatThrownBy(() -> service.createChild(merchant, "Shop", null, "shop.admin", null, null, null, maker))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((ApiException) e).getCode()).isEqualTo("CHILD_NOT_ALLOWED"));
        verify(tenants, never()).save(any());
    }
}
