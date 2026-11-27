/**
 * Unit-level gap probes (docs/onboarding-script-steps.md §"Gap register"). Each assertion
 * states the FIXED behaviour: it failed before the gap was closed and guards it after.
 */
import { describe, expect, it } from 'vitest';
import { MENU_ITEMS, firstCodedLink, menuFor, navEntries } from './shared/web-core/menu';
import { getWidget } from './shared/widgets/registry';
import { portalEligible } from './shared/web-core/session-core';
import type { SessionUser, TenantSummary } from './shared/web-core/session-core';
import * as configs from './portal-config';
import type { PortalConfig } from './portal-config';

const user = (tenantType: string): SessionUser =>
  ({ userId: 'u', username: 'u', fullName: 'U', tenantId: 't', tenantName: 'T', tenantType, roles: [] }) as unknown as SessionUser;
const tenant = (type: string, subType: string | null): TenantSummary =>
  ({ id: 't', type, subType, status: 'ACTIVE', legalName: 'T' }) as unknown as TenantSummary;

const allPortals = (): PortalConfig[] =>
  Object.values(configs).filter((v): v is PortalConfig => typeof v === 'object' && v !== null && 'basePath' in v);

describe('GAP-2 · every widget a menu point names is registered', () => {
  const ids = [...new Set(MENU_ITEMS.flatMap((i) => i.widgets))];
  it.each(ids)('%s is in the widgets registry', (id) => {
    expect(getWidget(id), `menu references widget "${id}"`).toBeDefined();
  });
});

describe('GAP-3/4 · approvals queue and plugin governance have menu points', () => {
  it('approvals.queue is reachable by every checker permission', () => {
    const item = MENU_ITEMS.find((i) => i.widgets.includes('approvals.queue'));
    expect(item?.perm).toEqual(expect.arrayContaining(['kyc.approve', 'je.approve', 'user.manage', 'role.manage', 'plugin.manage']));
  });
  it('the admin console keeps an approvals point (user/role changes are four-eyes)', () => {
    expect(menuFor('platform').some((i) => i.widgets.includes('approvals.queue'))).toBe(true);
  });
});

describe('ADMIN-MENU · the admin console shows the specified menus and submenus', () => {
  const all = [...new Set(MENU_ITEMS.flatMap((i) => i.perm))];
  it('sidebar is the spec: User Management → Back Office User, Reset Password, Customer, Organization; Role Management → Component, Sub Component, Branch, Department, Role, Approvals — no Onboarding menu', () => {
    const nav = navEntries('platform', () => true).map((e) => (e.kind === 'group' ? `#${e.label}` : e.label));
    expect(nav).toEqual(['#User Management', 'Back Office User', 'Reset Password', 'Customer', 'Organization',
      '#Role Management', 'Component', 'Sub Component', 'Branch', 'Department', 'Role', 'Approvals']);
  });
  it('menu points without a feature yet land on the placeholder and are still gated', () => {
    for (const label of ['Component', 'Sub Component', 'Department']) {
      const item = menuFor('platform').find((i) => i.label === label);
      expect(item?.widgets).toEqual(['menu.placeholder']);
      expect(item?.perm.length).toBeGreaterThan(0);
    }
  });
  it('Branch is the branches widget (four-eyes create / rename / delete), gated like Role', () => {
    const item = menuFor('platform').find((i) => i.label === 'Branch');
    expect(item?.widgets).toEqual(['org.branches']);
    expect(item?.perm).toEqual(['role.view', 'role.manage']);
    expect(item?.link).toBe('/branches');
  });
  it('Customer is the KYC plugin\'s customer widget (register → customer activates → checker activates), on both portals', () => {
    const platform = menuFor('platform').find((i) => i.label === 'Customer');
    expect(platform?.widgets).toEqual(['kyc.customers']);
    expect(platform?.perm).toEqual(['kyc.review']);
    const org = menuFor('org').find((i) => i.id === 'kyc-customers');
    expect(org?.widgets).toEqual(['kyc.customers']);
    expect(org?.group).toBe('KYC');
  });
  it('organization screen is the org directory with KYC status + "New onboarding" (KYC form), gated by its keys', () => {
    const org = menuFor('platform').find((i) => i.label === 'Organization');
    // one widget: the directory of the signed-in org's subtree; New onboarding opens the KYC form
    expect(org?.widgets).toEqual(['onboard.list']);
    expect(org?.config?.['onboard.list']).toEqual({ scope: 'own' });
    expect(org?.perm).toEqual(expect.arrayContaining(['kyc.review', 'org.create']));
  });
  it('home falls back to a point the portal actually has (never a missing /overview)', () => {
    expect(firstCodedLink('platform', all)).toBe('/users');
    expect(menuFor('platform').map((i) => i.link)).toContain(firstCodedLink('platform', ['role.view']));
    expect(firstCodedLink('platform', [])).toBeNull();
  });
  it('org portals keep their full menu', () => {
    expect(menuFor('org').map((i) => i.link)).toEqual(expect.arrayContaining(
      ['/overview', '/onboarding', '/network', '/users', '/kyc/reviews', '/kyc', '/approvals', '/bank-accounts',
        '/journal', '/audit', '/rbac', '/permissions', '/ledger', '/periods']));
  });
});

describe('every org portal (manufacturer / distributor / wholesaler / merchant) has Onboarding, User Management, Role Management + Inventory', () => {
  const group = (g: string) => menuFor('org').filter((i) => i.group === g);
  it('Onboarding (filing children through KYC + the network) is an org menu, never an admin one', () => {
    expect(group('Onboarding').map((i) => i.link)).toEqual(['/onboarding', '/network']);
    expect(menuFor('platform').some((i) => i.group === 'Onboarding' || i.link === '/onboarding')).toBe(false);
  });
  it('Role Management carries roles + the permission catalogue', () => {
    expect(group('Role Management').map((i) => i.widgets[0])).toEqual(['rbac.matrix', 'permissions.catalogue']);
  });
  it('User Management adds users to the signed-in organization (same widget as the admin console)', () => {
    const users = group('User Management');
    expect(users.map((i) => i.link)).toEqual(['/users', '/reset-password']);
    const list = users.find((i) => i.link === '/users');
    expect(list?.widgets).toEqual(['users.organization']);
    expect(list?.perm).toEqual(['user.manage']);
    const admin = menuFor('platform').find((i) => i.group === 'User Management' && i.widgets.includes('users.organization'));
    expect(admin?.link).toBe('/users');
  });
  it('Inventory is its own group, gated by inventory.view', () => {
    expect(group('Inventory').map((i) => [i.link, i.perm])).toEqual([['/inventory', ['inventory.view']]]);
  });
});

describe('GAP-1 · roles screen exists for organizations too', () => {
  it('rbac.matrix is offered on the org portals', () => {
    const portals = MENU_ITEMS.filter((i) => i.widgets.includes('rbac.matrix')).flatMap((i) => i.portals);
    expect(portals).toContain('org');
  });
});

describe('GAP-5 · manufacturer portal admits PSP tenants', () => {
  it('a PSP stamped MANUFACTURER is eligible', () => {
    expect(portalEligible(configs.PORTAL_MANUFACTURER, user('PSP'), tenant('PSP', 'MANUFACTURER'))).toBe(true);
  });
  it('a legacy PSP row with no subType is still eligible (type-aware fallback)', () => {
    expect(portalEligible(configs.PORTAL_MANUFACTURER, user('PSP'), tenant('PSP', null))).toBe(true);
  });
  it('a distributor is NOT eligible for the manufacturer portal', () => {
    expect(portalEligible(configs.PORTAL_MANUFACTURER, user('TENANT'), tenant('TENANT', 'DISTRIBUTOR'))).toBe(false);
  });
});

describe('GAP-6 · merchant portal entry exists', () => {
  it('a MERCHANT portal identity is configured at /merchant', () => {
    const merchant = allPortals().find((p) => p.tenantSubType === 'MERCHANT');
    expect(merchant?.basePath).toBe('/merchant');
  });
  it('merchant tenants are eligible only there', () => {
    const merchant = allPortals().find((p) => p.tenantSubType === 'MERCHANT')!;
    expect(portalEligible(merchant, user('TENANT'), tenant('TENANT', 'MERCHANT'))).toBe(true);
    expect(portalEligible(configs.PORTAL_DISTRIBUTOR, user('TENANT'), tenant('TENANT', 'MERCHANT'))).toBe(false);
  });
});

describe('GAP-RBAC-MENU · the role matrix reads the PUBLISHED menu of the role’s own portal', () => {
  it.each([
    ['PLATFORM', null, 'admin'],
    ['TENANT', 'MANUFACTURER', 'manufacturer'],
    ['PSP', null, 'manufacturer'], // legacy PSP row with no sub-type yet (admitsTypes)
    ['TENANT', 'DISTRIBUTOR', 'distributor'],
    ['TENANT', 'WHOLESALER', 'wholesaler'],
    ['TENANT', 'MERCHANT', 'merchant'],
  ])('%s/%s signs in to %s', (type, subType, key) => {
    expect(configs.deliveryKeyForTenant({ type, subType })).toBe(key);
  });
  it('an org with no portal has no menu (fail closed, never a guess)', () => {
    expect(configs.deliveryKeyForTenant({ type: 'TENANT', subType: null })).toBeNull();
    expect(configs.deliveryKeyForTenant({ type: 'TENANT', subType: 'UNKNOWN' })).toBeNull();
  });
});
