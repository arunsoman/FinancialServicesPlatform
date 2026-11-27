/**
 * THE menu registry — one canonical catalogue of every FSP portal's sidebar
 * (ported from SME shared/web-core/menu.ts; permission KEYS are OURS —
 * plans/ui-portals-plan.md §2.4/§6.2, flat seeded keys, no verb grid).
 *
 * Rules (fail-closed, same as SME):
 *  1. One menu point, one label — everywhere it renders.
 *  2. One menu point, one permission-key list (OR semantics) — the sidebar
 *     and every route guard read this same registry.
 *  3. No ungated items: an unmapped path denies (route guards use the
 *     '__no_menu_point__' sentinel); an empty perm list shows nothing.
 *  4. A shared page is gated by the union of the rows its panels need.
 *
 * Keys must exist in the FSP `permissions` table (V2 seed). The screens are
 * widget compositions (layouts land next); until then each point maps to a
 * coded screen id in the portal's route table.
 */
export type PortalId = 'platform' | 'org';

export type MenuWidget =
  | 'kyc.orgs' | 'kyc.manage' | 'kyc.application' | 'kyc.customers'
  | 'onboard.psp' | 'onboard.list'
  | 'approvals.queue' | 'journal.entry' | 'audit.trail'
  | 'rbac.matrix' | 'permissions.catalogue' | 'plugins.governance'
  | 'users.organization' | 'users.reset' | 'org.branches'
  | 'ledger.accounts' | 'period.control' | 'bank.accounts'
  | 'overview.tiles'
  | 'inventory.placeholder'
  | 'menu.placeholder';

export interface MenuItem {
  id: string;
  /** THE label — one name per menu point, everywhere. */
  label: string;
  icon: string;
  /** Route path relative to the portal base. */
  link: string;
  /** Permission keys (OR semantics). Required. */
  perm: string[];
  /** Optional sidebar group header, rendered once when the group changes. */
  group?: string;
  portals: PortalId[];
  widgets: MenuWidget[];
  /** Per-widget config for THIS screen (seeded into the layout; validated by each widget's schema). */
  config?: Partial<Record<MenuWidget, Record<string, unknown>>>;
  /** Per-widget column spans for THIS screen (clamped to each widget's min..max). */
  span?: Partial<Record<MenuWidget, Partial<Record<'xs' | 'sm' | 'md' | 'lg', number>>>>;
}

/** The approvals queue's gate: every checker permission (OR). One list for both portals. */
const APPROVALS_PERMS = ['kyc.approve', 'kyc.reject', 'je.approve', 'org.approve_child', 'bank.account.approve',
  'gl.account.approve', 'user.manage', 'role.manage', 'plugin.manage', 'ui.layout.manage'];

export const MENU_ITEMS: MenuItem[] = [
  // ================= platform (admin console) — the admin sidebar spec, menu for menu:
  //   User Management → Back Office User, Reset Password, Customer, Organization
  //   Role Management → Component, Sub Component, Branch, Department, Role
  // plus Approvals (user and role changes are four-eyes and are decided only there). No separate
  // Onboarding menu here: Organization is the org directory AND its "New onboarding" entry.
  // Component / Sub Component / Department have no feature
  // behind them yet: they land on menu.placeholder, gated by the key their feature will need.
  // Branch is org.branches (four-eyes create / rename / delete; rbac.matrix scopes roles by branch).
  { id: 'back-office-users', label: 'Back Office User', icon: '☰', link: '/users',
    perm: ['user.manage'], portals: ['platform'], group: 'User Management', widgets: ['users.organization'] },
  { id: 'reset-password', label: 'Reset Password', icon: '⚷', link: '/reset-password',
    perm: ['user.manage'], portals: ['platform'], group: 'User Management', widgets: ['users.reset'] },
  // customer KYC (the KYC plugin's widget): register → customer activates → checker activates
  { id: 'customers', label: 'Customer', icon: '☺', link: '/customers',
    perm: ['kyc.review'], portals: ['platform'], group: 'User Management', widgets: ['kyc.customers'] },
  // the organization directory: every org below the platform with its status + KYC standing
  // (case, risk), and "New onboarding" opening the KYC application form in a drawer (the
  // ladder's next rung under the signed-in org). Gated by the union of the widget's keys.
  { id: 'organizations', label: 'Organization', icon: '◈', link: '/organizations',
    perm: ['kyc.review', 'org.create'], portals: ['platform'], group: 'User Management',
    widgets: ['onboard.list'],
    config: { 'onboard.list': { scope: 'own' } } },
  { id: 'components', label: 'Component', icon: '▦', link: '/components',
    perm: ['role.manage'], portals: ['platform'], group: 'Role Management', widgets: ['menu.placeholder'] },
  { id: 'sub-components', label: 'Sub Component', icon: '▤', link: '/sub-components',
    perm: ['role.manage'], portals: ['platform'], group: 'Role Management', widgets: ['menu.placeholder'] },
  { id: 'branches', label: 'Branch', icon: '⌂', link: '/branches',
    perm: ['role.view', 'role.manage'], portals: ['platform'], group: 'Role Management', widgets: ['org.branches'] },
  { id: 'departments', label: 'Department', icon: '⊞', link: '/departments',
    perm: ['role.manage'], portals: ['platform'], group: 'Role Management', widgets: ['menu.placeholder'] },
  { id: 'roles', label: 'Role', icon: '⚿', link: '/rbac',
    perm: ['role.view', 'role.manage'], portals: ['platform'], group: 'Role Management', widgets: ['rbac.matrix'] },
  { id: 'platform-approvals', label: 'Approvals', icon: '✓', link: '/approvals',
    perm: APPROVALS_PERMS, portals: ['platform'], group: 'Role Management', widgets: ['approvals.queue'] },

  // ================= org portals (manufacturer / distributor / wholesaler / merchant)
  // ---------------- governance (governance-first like SME)
  { id: 'overview', label: 'Overview', icon: '◫', link: '/overview',
    perm: ['audit.view'], portals: ['org'], widgets: ['overview.tiles'] },
  // ---------------- Onboarding: organizations onboard children through the KYC plugin
  { id: 'onboarding', label: 'Onboarding', icon: '⇲', link: '/onboarding',
    perm: ['org.onboard_child'], portals: ['org'], group: 'Onboarding', widgets: ['kyc.manage'] },
  { id: 'network', label: 'My network', icon: '⑃', link: '/network',
    perm: ['kyc.review'], portals: ['org'], group: 'Onboarding', widgets: ['kyc.orgs'] },

  // ---------------- User Management (same widget as the admin console's Back Office User):
  // add users to THIS organization — four-eyes USER_CREATE, then the IdP sends the new user
  // an activation (email link / SMS code) and they set their own password. Nobody else does.
  { id: 'users', label: 'Users', icon: '☰', link: '/users',
    perm: ['user.manage'], portals: ['org'], group: 'User Management', widgets: ['users.organization'] },
  { id: 'org-reset-password', label: 'Reset password', icon: '⚷', link: '/reset-password',
    perm: ['user.manage'], portals: ['org'], group: 'User Management', widgets: ['users.reset'] },

  // ---------------- Role Management (same widgets as the admin console's Role Management)
  { id: 'rbac', label: 'Roles and permissions', icon: '⚿', link: '/rbac',
    perm: ['role.view', 'role.manage'], portals: ['org'], group: 'Role Management', widgets: ['rbac.matrix'] },
  { id: 'permissions', label: 'All permissions', icon: '⌗', link: '/permissions',
    perm: ['audit.view', 'role.manage', 'kyc.review'], portals: ['org'],
    group: 'Role Management', widgets: ['permissions.catalogue'] },

  // ---------------- Inventory (the inventory plugin plane; its widgets land on these screens)
  { id: 'inventory', label: 'Inventory', icon: '▦', link: '/inventory',
    perm: ['inventory.view'], portals: ['org'], group: 'Inventory', widgets: ['inventory.placeholder'] },

  // ---------------- KYC (one screen family: the directory / queue / case)
  { id: 'kyc-reviews', label: 'Review queue', icon: '✓', link: '/kyc/reviews',
    perm: ['kyc.approve', 'kyc.reject'], portals: ['org'], group: 'KYC', widgets: ['kyc.manage'] },
  { id: 'kyc-customers', label: 'Customers', icon: '☺', link: '/kyc/customers',
    perm: ['kyc.review'], portals: ['org'], group: 'KYC', widgets: ['kyc.customers'] },
  { id: 'kyc-decisions', label: 'My organization', icon: '◈', link: '/kyc',
    perm: ['kyc.review'], portals: ['org'], group: 'KYC', widgets: ['kyc.application'] },

  // ---------------- operations (money-adjacent: four-eyes surfaces)
  { id: 'approvals', label: 'Approvals', icon: '✓', link: '/approvals',
    perm: APPROVALS_PERMS, portals: ['org'], group: 'Operations', widgets: ['approvals.queue'] },
  { id: 'bank-accounts', label: 'Bank accounts', icon: '₪', link: '/bank-accounts',
    perm: ['bank.account.view'], portals: ['org'], group: 'Operations', widgets: ['bank.accounts'] },
  { id: 'journal', label: 'Journal entries', icon: '⇄', link: '/journal',
    perm: ['je.view', 'je.create', 'je.approve'], portals: ['org'],
    group: 'Operations', widgets: ['journal.entry'] },
  { id: 'ledger-audit', label: 'Audit trail', icon: '☰', link: '/audit',
    perm: ['audit.view'], portals: ['org'], group: 'Operations', widgets: ['audit.trail'] },

  // ---------------- ledger setup
  { id: 'ledger', label: 'Chart of accounts', icon: '▣', link: '/ledger',
    perm: ['gl.account.view'], portals: ['org'], group: 'Ledger', widgets: ['ledger.accounts'] },
  { id: 'periods', label: 'Accounting periods', icon: '◷', link: '/periods',
    perm: ['gl.account.view', 'period.open', 'period.close'], portals: ['org'],
    group: 'Ledger', widgets: ['period.control'] },
];

export function menuFor(portal: PortalId): MenuItem[] {
  return MENU_ITEMS.filter((i) => i.portals.includes(portal));
}

/** [route, permission keys] for one portal's menu. */
export function menuPerms(portal: PortalId): Array<readonly [string, string[]]> {
  return menuFor(portal).map((i) => [i.link, i.perm] as const);
}

/** First menu point (registry order) the holder of `permissions` may open; null = none. */
export function firstCodedLink(portal: PortalId, permissions: readonly string[]): string | null {
  return menuFor(portal).find((i) => i.perm.some((k) => permissions.includes(k)))?.link ?? null;
}

/** Exact permission set for one route ('__no_menu_point__' sentinel ⇒ fail-closed deny). */
export function permsForRoute(portal: PortalId, link: string): string[] {
  return menuFor(portal).find((i) => i.link === link)?.perm ?? ['__no_menu_point__'];
}

export type MenuNavEntry =
  | { kind: 'group'; label: string }
  | { kind: 'item'; label: string; icon: string; link: string; icon2?: string };

/**
 * Flattens a portal's menu into sidebar render entries: drop refused points,
 * emit each group header once when the first point of the group shows.
 */
export function navEntries(
  portal: PortalId,
  visible: (item: MenuItem) => boolean,
): MenuNavEntry[] {
  const out: MenuNavEntry[] = [];
  let lastGroup: string | undefined;
  for (const item of menuFor(portal)) {
    if (!visible(item)) continue;
    if (item.group && item.group !== lastGroup) out.push({ kind: 'group', label: item.group });
    lastGroup = item.group;
    out.push({ kind: 'item', label: item.label, icon: item.icon, link: item.link });
  }
  return out;
}
