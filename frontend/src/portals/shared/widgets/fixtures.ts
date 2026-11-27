/**
 * Fixture answers per logical source (scenario → body), shared by the widget workbench's
 * widget frames and builder canvas. Bodies mirror the wire DTOs (records), so a widget
 * that renders here renders against the live API. Scenarios: default · empty · error ·
 * not-ready (the gateway's 503 PLUGIN_NOT_READY). Plugin widgets contribute resolvers that
 * play their server (kyc/manage/fixture-server.ts).
 */
import type { FixtureRequest, FixtureScenario, FixtureSet } from './core/ports';
import { ApiError } from '../web-core/api';
import { KYC_MANAGE_FIXTURES } from './kyc/manage/fixture-server';
import { WIDGET_DATA } from './core/sources';

export type Scenario = FixtureScenario;
export type { FixtureSet };
export const SCENARIOS: readonly Scenario[] = ['default', 'empty', 'error', 'not-ready'];

/** What each scenario should make a widget show (the workbench scenario picker label). */
export const SCENARIO_EXPECT: Record<Scenario, string> = {
  default: 'ready',
  empty: 'empty state',
  error: 'error state',
  'not-ready': 'plugin not enabled (503)',
};

const WB_ORG = '00000000-0000-0000-0000-0000000000a1';
const WB_ROLES: Record<string, string> = { 'org-admin': 'Organization admin', maker: 'Maker', checker: 'Checker' };
function wbUser(n: string, username: string, fullName: string, status: string, roles: string[], activated: boolean | null = null) {
  const ts = '2026-11-01T09:00:00Z';
  return {
    id: `00000000-0000-0000-0000-0000000002${n}`, tenantId: WB_ORG, username, fullName,
    email: `${username}@example.com`, phone: null, status, isApplicant: false, checker: false,
    roles: roles.map((k, i) => ({ id: `00000000-0000-0000-0000-0000000003a${i + 1}`, key: k, name: WB_ROLES[k] ?? k })),
    version: 0, createdAt: ts, updatedAt: ts, archivedAt: status === 'ARCHIVED' ? ts : null,
    primaryContact: activated === null ? null : 'EMAIL', activated,
  };
}

const WB_USERS = [
  wbUser('d1', 'anna.shikongo', 'Anna Shikongo', 'ACTIVE', ['org-admin']),
  wbUser('d2', 'johannes.kapuka', 'Johannes Kapuka', 'ACTIVE', ['maker']),
  wbUser('d3', 'maria.nghipondoka', 'Maria Nghipondoka', 'DISABLED', ['checker']),
  wbUser('d4', 'peter.amutenya', 'Peter Amutenya', 'ACTIVE', []),
  wbUser('d5', 'old.account', 'Old Account', 'ARCHIVED', []),
  wbUser('d6', 'ndeshi.amukoto', 'Ndeshi Amukoto', 'ACTIVE', ['maker'], false), // approved, awaiting IdP activation
];

type WbUser = ReturnType<typeof wbUser>;

/** The fixture lane's stand-in for core's paged user list (same params, same envelope). */
function fixtureUserPage(all: WbUser[], params: Record<string, string>) {
  const page = Math.max(0, Number(params.page ?? 0) || 0);
  const size = Math.min(100, Math.max(1, Number(params.size ?? 20) || 20));
  const status = (params.status ?? '').toUpperCase();
  const q = (params.q ?? '').trim().toLowerCase();
  const [field, dir] = (params.sort || 'username,asc').split(',') as [keyof WbUser, string];
  const rows = all
    .filter((u) => (!status ? true : status === 'CURRENT' ? u.status !== 'ARCHIVED' : u.status === status))
    .filter((u) => !q || [u.username, u.fullName.toLowerCase(), u.email ?? '', ...u.roles.map((x) => x.name.toLowerCase())]
      .some((v) => v.includes(q)))
    .sort((a, b) => String(a[field]).localeCompare(String(b[field])) * (dir === 'desc' ? -1 : 1));
  const counts: Record<string, number> = { PENDING: 0, ACTIVE: 0, DISABLED: 0, ARCHIVED: 0 };
  for (const u of all) counts[u.status] = (counts[u.status] ?? 0) + 1;
  counts.CURRENT = counts.PENDING + counts.ACTIVE + counts.DISABLED;
  const totalPages = Math.ceil(rows.length / size);
  return {
    items: rows.slice(page * size, page * size + size), page, size, totalElements: rows.length, totalPages,
    hasNext: page + 1 < totalPages, sort: params.sort || 'username,asc', counts,
    org: { id: WB_ORG, legalName: 'Namibia Coffee Roasters (Pty) Ltd', type: 'TENANT', subType: 'MANUFACTURER', depth: 2 },
  };
}

/** fixtures contributed by widget folders' data.ts (built-in entries below win on a clash) */
const CONTRIBUTED: FixtureSet = Object.assign({}, ...Object.values(WIDGET_DATA).map((m) => (m.fixtures ?? {}) as FixtureSet));

export const FIXTURES: FixtureSet = {
  ...CONTRIBUTED,
  ...KYC_MANAGE_FIXTURES,
  'kyc.orgs.self': {
    default: {
      id: '00000000-0000-0000-0000-0000000000a1',
      legalName: 'Namibia Coffee Roasters (Pty) Ltd',
      subType: 'MANUFACTURER',
      status: 'ACTIVE',
      type: 'TENANT', depth: 2, hierarchyPath: '/1/a1',
    },
    empty: null,
    error: null,
  },
  'kyc.orgs.tree': {
    default: [
      { id: '00000000-0000-0000-0000-0000000000b1', legalName: 'Coastal Distributors CC', subType: 'DISTRIBUTOR', status: 'PENDING', type: 'TENANT' },
      { id: '00000000-0000-0000-0000-0000000000c1', legalName: 'North Wholesalers', subType: 'WHOLESALER', status: 'ACTIVE', type: 'TENANT' },
    ],
    empty: [],
    error: null,
  },
  'users.roles': {
    default: [
      { id: '00000000-0000-0000-0000-0000000003a1', key: 'org-admin', name: 'Organization admin', scope: 'TENANT', permissionCount: 9, assignable: true },
      { id: '00000000-0000-0000-0000-0000000003a2', key: 'maker', name: 'Maker', scope: 'TENANT', permissionCount: 4, assignable: true },
      { id: '00000000-0000-0000-0000-0000000003a3', key: 'checker', name: 'Checker', scope: 'TENANT', permissionCount: 3, assignable: false },
    ],
    empty: [],
    error: null,
  },
  // plays core's paged list (UserAdminService.list): search / status / sort / page on the "server"
  'users.list': {
    default: (r: FixtureRequest) => fixtureUserPage(WB_USERS, r.params),
    empty: (r: FixtureRequest) => fixtureUserPage([], r.params),
    error: null,
  },
  'users.get': {
    default: (r: FixtureRequest) => {
      const u = WB_USERS.find((x) => x.id === r.params.personId);
      if (!u) throw new ApiError({ status: 404, code: 'PERSON_NOT_FOUND', title: 'Not found', detail: 'User not found' });
      return u;
    },
    error: null,
  },
};
