package com.fsp.core.tenant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PLATFORM → MANUFACTURER → DISTRIBUTOR → WHOLESALER → MERCHANT: each org onboards only the next rung. */
class TenantLadderTest {

    private static Tenant org(String type, String subType) {
        Tenant t = new Tenant();
        t.type = type;
        t.subType = subType;
        return t;
    }

    @Test
    void eachRungOnboardsOnlyTheNextOne() {
        assertThat(TenantLadder.childSubTypeOf(org("PLATFORM", null))).isEqualTo("MANUFACTURER");
        assertThat(TenantLadder.childSubTypeOf(org("PSP", "MANUFACTURER"))).isEqualTo("DISTRIBUTOR");
        assertThat(TenantLadder.childSubTypeOf(org("TENANT", "DISTRIBUTOR"))).isEqualTo("WHOLESALER");
        assertThat(TenantLadder.childSubTypeOf(org("TENANT", "WHOLESALER"))).isEqualTo("MERCHANT");
    }

    @Test
    void merchantsAndOffLadderOrgsOnboardNothing() {
        assertThat(TenantLadder.childSubTypeOf(org("TENANT", "MERCHANT"))).isNull();
        assertThat(TenantLadder.childSubTypeOf(org("TENANT", "DEALER"))).isNull();
        assertThat(TenantLadder.childSubTypeOf(org("TENANT", null))).isNull();
    }
}
