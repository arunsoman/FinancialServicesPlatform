package com.fsp.core.rbac;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * THE permission templates for the two roles every new organization is born with
 * ({@code TENANT_ADMIN} = maker side, {@code TENANT_CHECKER} = checker side).
 *
 * <p>One source of truth for {@code TenantService} (platform → PSP) and
 * {@code TenantChildrenService} (KYC saga → child org), and mirrored by migration
 * {@code V19__roles_governance.sql} for organizations provisioned before it existed.
 *
 * <p>Rules (docs/onboarding-script-steps.md GAP-1):
 * <ul>
 *   <li>Every admin can run its own organization: users ({@code user.manage}), roles
 *       ({@code role.view}/{@code role.manage}), ledger maker keys, audit.</li>
 *   <li>Only NON-LEAF organizations onboard children ({@code org.onboard_child},
 *       {@code org.create}, {@code screening.run}). {@code MERCHANT} is the leaf of the
 *       supply chain and never gets them.</li>
 *   <li>Every checker can decide the four-eyes requests its organization raises: KYC,
 *       users, roles, journal, GL, bank accounts. The checker holding {@code user.manage}
 *       /{@code role.manage} is what lets it resolve USER_* / ROLE_* approvals
 *       ({@code ApprovalService.requirePermissionFor}); four-eyes is still person-anchored.
 *       Non-leaf checkers additionally consent to their own tenant's PLUGIN_INSTALL
 *       ({@code plugin.manage}, V20).</li>
 * </ul>
 * Granting is still bounded per actor at runtime: nobody can grant a key they do not hold
 * ({@code RoleAdminService}, {@code UserAdminService}).
 */
public final class RoleTemplates {

    public static final String ADMIN_ROLE = "TENANT_ADMIN";
    public static final String CHECKER_ROLE = "TENANT_CHECKER";

    /** Organization sub-types at the bottom of the supply chain: they never onboard children. */
    public static final Set<String> LEAF_SUB_TYPES = Set.of("MERCHANT");

    private static final List<String> ADMIN_BASE = List.of(
        "je.create", "je.view", "gl.account.create", "gl.account.view", "bank.account.create", "bank.account.view",
        "period.open", "period.close", "audit.view", "notification.view",
        "inventory.view", "inventory.sku.manage", "inventory.stock.upload",
        "digital.view", "digital.product.manage", "digital.voucher.upload", "digital.float.deposit", "digital.dispense",
        "je.post.immediate",
        // invoicing (V40): an org bills its buyers and pays its suppliers
        "invoice.view", "invoice.create", "invoice.pay", "invoice.collect", "invoice.void", "invoice.void.approve",
        "kyc.review", "user.manage", "role.view", "role.manage");

    private static final List<String> ADMIN_ONBOARDING = List.of(
        "org.onboard_child", "org.create", "screening.run");

    private static final List<String> CHECKER_BASE = List.of(
        "je.approve", "je.view", "gl.account.approve", "gl.account.view", "bank.account.approve", "bank.account.view", "audit.view",
        "notification.view",
        "inventory.view", "inventory.pricing.approve", "inventory.stock.approve",
        "digital.view", "digital.voucher.authorize",
        "invoice.view", "invoice.void.approve",
        "kyc.review", "kyc.approve", "kyc.reject", "user.manage", "role.view", "role.manage");

    /** Non-leaf orgs hold the plugin-consent checker key — see V20__psp_plugin_consent.sql. */
    private static final List<String> CHECKER_PLUGIN_CONSENT = List.of("plugin.manage");

    private RoleTemplates() {}

    /** Maker-side keys for an organization of this sub-type ({@code null} = PSP root of a chain). */
    public static List<String> adminKeys(String subType) {
        Set<String> keys = new LinkedHashSet<>(ADMIN_BASE);
        if (!isLeaf(subType)) keys.addAll(ADMIN_ONBOARDING);
        return List.copyOf(keys);
    }

    /** Checker-side keys; child organizations additionally activate their own children. */
    public static List<String> checkerKeys(String subType, boolean child) {
        Set<String> keys = new LinkedHashSet<>(CHECKER_BASE);
        if (child) keys.add("org.activate");
        // A checker decides its own tenant's PLUGIN_INSTALL (customer-consent model, V20);
        // leaves (MERCHANT) never install or consent to plugins in Phase-0.
        if (!isLeaf(subType)) keys.addAll(CHECKER_PLUGIN_CONSENT);
        return List.copyOf(keys);
    }

    public static boolean isLeaf(String subType) {
        return subType != null && LEAF_SUB_TYPES.contains(subType.toUpperCase(Locale.ROOT));
    }
}
