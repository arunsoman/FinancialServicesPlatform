package com.fsp.psp.merchant;

import com.fsp.psp.common.ApiException;
import com.fsp.psp.common.KeyLocks;
import com.fsp.psp.config.PspProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 5 — merchant lifecycle tests (plan §11): onboarding validation (PSD-1 §12.2.3
 * pay-over ceiling), maker-lifecycle transitions (activate/suspend), tenant-scoped 404s,
 * and the acquirable precondition for acquiring.
 */
class MerchantServiceTest {

    MerchantRepository merchants;
    MerchantService service;

    final UUID tenantId = UUID.fromString("11111111-2222-4333-8444-555555555555");

    @BeforeEach
    void setUp() {
        merchants = mock(MerchantRepository.class);
        service = new MerchantService(merchants, new PspProperties(), new KeyLocks());
        when(merchants.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(merchants.findByMerchantCode(any())).thenReturn(Optional.empty());
    }

    Merchant row(String status) {
        Merchant m = new Merchant();
        m.id = UUID.randomUUID();
        m.tenantId = tenantId;
        m.merchantCode = "m-1";
        m.displayName = "M";
        m.status = status;
        m.createdAt = Instant.now();
        m.updatedAt = m.createdAt;
        return m;
    }

    @Test
    void onboardValidatesAndDefaultsPayOver() {
        Merchant m = service.onboard(tenantId, "acme", "Acme Traders", null, null);
        assertThat(m.status).isEqualTo(Merchant.STATUS_PENDING);
        assertThat(m.payOverDays).isEqualTo(2); // PspProperties default
        assertThat(m.merchantCode).isEqualTo("acme");
    }

    @Test
    void onboardRejectsBadCodesAndCeilings() {
        assertThatThrownBy(() -> service.onboard(tenantId, "bad code!", "X", null, null))
            .extracting(e -> ((ApiException) e).getCode()).isEqualTo("PSP_INPUT_INVALID");
        assertThatThrownBy(() -> service.onboard(tenantId, "ok", "", null, null))
            .extracting(e -> ((ApiException) e).getCode()).isEqualTo("PSP_INPUT_INVALID");
        assertThatThrownBy(() -> service.onboard(tenantId, "ok", "X", null, 0))
            .extracting(e -> ((ApiException) e).getCode()).isEqualTo("PSP_INPUT_INVALID");
        assertThatThrownBy(() -> service.onboard(tenantId, "ok", "X", null, 3))
            .extracting(e -> ((ApiException) e).getCode()).isEqualTo("PSP_INPUT_INVALID");
    }

    @Test
    void duplicateCodeConflicts() {
        when(merchants.findByMerchantCode("acme")).thenReturn(Optional.of(row("PENDING")));
        assertThatThrownBy(() -> service.onboard(tenantId, "acme", "X", null, null))
            .extracting(e -> ((ApiException) e).getCode()).isEqualTo("PSP_MERCHANT_CODE_TAKEN");
    }

    @Test
    void transitionsEnforceStateMachine() {
        UUID id = UUID.randomUUID();
        when(merchants.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.of(row("PENDING")));

        Merchant m = service.activate(tenantId, id);
        assertThat(m.status).isEqualTo(Merchant.STATUS_ACTIVE);

        // replaying activate on ACTIVE is a conflict (maker transition precondition)
        assertThatThrownBy(() -> service.activate(tenantId, id))
            .extracting(e -> ((ApiException) e).getCode()).isEqualTo("PSP_MERCHANT_STATE");
    }

    @Test
    void foreignTenantSees404() {
        UUID id = UUID.randomUUID();
        when(merchants.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.byId(tenantId, id)).isInstanceOfSatisfying(ApiException.class,
            e -> {
                assertThat(e.getCode()).isEqualTo("PSP_MERCHANT_NOT_FOUND");
                assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
            });
    }

    @Test
    void suspendingRequiresActive() {
        UUID id = UUID.randomUUID();
        when(merchants.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.of(row("PENDING")));
        assertThatThrownBy(() -> service.suspend(tenantId, id))
            .extracting(e -> ((ApiException) e).getCode()).isEqualTo("PSP_MERCHANT_STATE");
    }

    @Test
    void suspendedMerchantNotAcquirable() {
        assertThatThrownBy(() -> MerchantService.requireAcquirable(row("SUSPENDED")))
            .extracting(e -> ((ApiException) e).getCode()).isEqualTo("PSP_MERCHANT_NOT_ACQUIRABLE");
        MerchantService.requireAcquirable(row("ACTIVE")); // no throw
    }

    @Test
    void listIsServerSidePaged() {
        UUID id = UUID.randomUUID();
        when(merchants.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.of(row("ACTIVE")));
        var pageable = MerchantService.pageOf(0, 20, "merchantCode,asc");
        assertThat(pageable.getSort().getOrderFor("merchantCode"))
            .extracting(o -> o.getDirection().name()).isEqualTo("ASC");
        assertThatThrownBy(() -> MerchantService.pageOf(0, 20, "tenantId,asc"))
            .extracting(e -> ((ApiException) e).getCode()).isEqualTo("PSP_SORT_INVALID");
        assertThatThrownBy(() -> MerchantService.pageOf(-1, 20, null))
            .extracting(e -> ((ApiException) e).getCode()).isEqualTo("PSP_PAGE_INVALID");
        assertThatThrownBy(() -> MerchantService.pageOf(0, 101, null))
            .extracting(e -> ((ApiException) e).getCode()).isEqualTo("PSP_PAGE_INVALID");
    }
}
