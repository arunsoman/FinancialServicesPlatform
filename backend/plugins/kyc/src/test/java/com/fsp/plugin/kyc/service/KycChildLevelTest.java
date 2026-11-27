package com.fsp.plugin.kyc.service;

import com.fsp.plugin.kyc.api.ApiProblemException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An organization files KYC only for its CHILD level (core TenantLadder: manufacturer →
 * distributor → wholesaler → merchant). Before: the plugin accepted any sub-type, created the
 * case, and only core's refusal at the saga's second step stopped a sibling — leaving a stalled
 * TENANT_PENDING case. Now the level is checked before anything persists.
 */
class KycChildLevelTest {

    @Test
    void theChildLevelIsAccepted() {
        assertThatCode(() -> KycOnboardingSagaService.requireChildLevel("WHOLESALER", "wholesaler")).doesNotThrowAnyException();
    }

    @Test
    void aSiblingOrAHigherLevelIsRefused() {
        // a distributor (child = WHOLESALER) tries to onboard another distributor / a manufacturer
        assertThatThrownBy(() -> KycOnboardingSagaService.requireChildLevel("WHOLESALER", "DISTRIBUTOR"))
            .isInstanceOfSatisfying(ApiProblemException.class, e -> org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo("CHILD_SUBTYPE_NOT_ALLOWED"));
        assertThatThrownBy(() -> KycOnboardingSagaService.requireChildLevel("WHOLESALER", "MANUFACTURER"))
            .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> KycOnboardingSagaService.requireChildLevel("WHOLESALER", null))
            .isInstanceOf(ApiProblemException.class);
    }

    @Test
    void aLeafOnboardsNothing() {
        assertThatThrownBy(() -> KycOnboardingSagaService.requireChildLevel(null, "MERCHANT"))
            .isInstanceOfSatisfying(ApiProblemException.class, e -> org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo("CHILD_NOT_ALLOWED"));
    }
}
