package com.fsp.core.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** GAP-1: what a freshly provisioned organization's anchor roles can do (no SQL shim needed). */
class RoleTemplatesTest {

    @Test
    void pspAdminRunsItsOrgAndOnboardsChildren() {
        assertThat(RoleTemplates.adminKeys("MANUFACTURER")).contains(
            "org.onboard_child", "org.create", "screening.run", "kyc.review", "user.manage", "role.view", "role.manage",
            "je.create", "audit.view");
    }

    @Test
    void merchantIsALeafAndNeverOnboardsChildren() {
        assertThat(RoleTemplates.adminKeys("MERCHANT"))
            .contains("user.manage", "role.manage", "kyc.review")
            .doesNotContain("org.onboard_child", "org.create", "screening.run");
        assertThat(RoleTemplates.isLeaf("merchant")).isTrue();
        assertThat(RoleTemplates.isLeaf("DISTRIBUTOR")).isFalse();
    }

    @Test
    void checkerCanDecideEveryRequestItsOrgRaises() {
        assertThat(RoleTemplates.checkerKeys("MANUFACTURER", false)).contains(
            "kyc.approve", "kyc.reject", "kyc.review", "user.manage", "role.manage", "je.approve",
            "plugin.manage") // V20: non-leaf checkers consent to their own tenant's plugin installs
            .doesNotContain("org.activate");
        assertThat(RoleTemplates.checkerKeys("DISTRIBUTOR", true)).contains("org.activate");
    }

    @Test
    void leafCheckersNeverConsentToPluginInstalls() {
        assertThat(RoleTemplates.checkerKeys("MERCHANT", false))
            .doesNotContain("plugin.manage", "org.activate");
    }

    @Test
    void everyOrgBillsAndPaysAndItsCheckerSeesInvoices() {
        for (String subType : new String[] {"MANUFACTURER", "DISTRIBUTOR", "WHOLESALER", "MERCHANT"}) {
            assertThat(RoleTemplates.adminKeys(subType))
                .contains("invoice.view", "invoice.create", "invoice.pay", "invoice.collect", "invoice.void", "invoice.void.approve");
            assertThat(RoleTemplates.checkerKeys(subType, true)).contains("invoice.view", "invoice.void.approve");
        }
    }

    @Test
    void makerTemplateCarriesNoCheckerOnlyKeys() {
        assertThat(RoleTemplates.adminKeys("DISTRIBUTOR"))
            .doesNotContain("je.approve", "kyc.approve", "gl.account.approve", "bank.account.approve");
    }
}
