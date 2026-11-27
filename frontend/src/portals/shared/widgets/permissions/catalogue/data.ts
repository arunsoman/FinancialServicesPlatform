/** permissions.catalogue data contributions — the global permission catalog (core RbacController). */
import type { DataSources } from '../../core/sources';
import type { FixtureSet } from '../../core/ports';

export const sources: DataSources = {
  'permissions.catalogue.list': { '*': { method: 'GET', path: '/api/permissions' } },
};

const catalogue = [
  { key: 'audit.view', title: 'View the audit trail', risk: 'READ', component: 'audit' },
  { key: 'gl.account.view', title: 'View books and the chart of accounts', risk: 'READ', component: 'ledger' },
  { key: 'gl.account.create', title: 'Provision a default chart of accounts', risk: 'WRITE', component: 'ledger' },
  { key: 'je.create', title: 'Create and submit journal entries', risk: 'WRITE', component: 'journal' },
  { key: 'je.view', title: 'View journal entries', risk: 'READ', component: 'journal' },
  { key: 'je.approve', title: 'Approve and post journal entries', risk: 'FOUR_EYES', component: 'journal' },
  { key: 'org.create', title: 'Onboard organizations', risk: 'WRITE', component: 'onboarding' },
  { key: 'role.view', title: 'View roles and permission assignments', risk: 'READ', component: 'rbac' },
  { key: 'role.manage', title: 'Create and change roles (four-eyes)', risk: 'FOUR_EYES', component: 'rbac' },
  { key: 'user.manage', title: 'Invite and administer users', risk: 'WRITE', component: 'identity' },
  { key: 'kyc.review', title: 'Review KYC cases (four-eyes)', risk: 'FOUR_EYES', component: 'kyc' },
  { key: 'plugin.manage', title: 'Govern platform plugins', risk: 'FOUR_EYES', component: 'plugins' },
  { key: 'plugin.register', title: 'Request a plugin registration', risk: 'FOUR_EYES', component: 'plugins' },
  { key: 'plugin.install', title: 'Request a plugin installation', risk: 'FOUR_EYES', component: 'plugins' },
  { key: 'ui.widget.register', title: 'Register UI widgets', risk: 'FOUR_EYES', component: 'ui' },
  { key: 'ui.layout.manage', title: 'Edit portal layouts', risk: 'WRITE', component: 'ui' },
  { key: 'tenant.act_as', title: 'Open an act-as session into a child tenant', risk: 'FOUR_EYES', component: 'tenancy' },
] as const;

/** The catalog needs no permission — any authenticated platform actor may list it. */
export const fixtures: FixtureSet = {
  'permissions.catalogue.list': {
    default: catalogue.map((p) => ({ ...p })),
    empty: [],
    error: null,
  },
};
