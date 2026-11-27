/**
 * onboard.list data contributions — the organization directory for onboarding.
 * Server-side page (totals-free): page/size/q/sort are allow-listed query params.
 */
import type { DataSources } from '../../core/sources';
import type { FixtureRequest, FixtureSet } from '../../core/ports';
import type { OrgListQuery, OrgPage, OrgRow } from './model';
import { DEFAULT_QUERY, PAGE_SIZES } from './model';
import { forbidUnless } from '../../kit/forbid';

export const sources: DataSources = {
  'onboard.orgs.list': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/children/page', query: ['page', 'size', 'q', 'sort'] } },
};

const all: OrgRow[] = [
  { id: '00000000-0000-0000-0000-0000000000b1', legalName: 'Swipe Payment Services (Pty) Ltd', subType: 'MANUFACTURER', type: 'TENANT', status: 'ACTIVE', verificationStatus: 'APPROVED', createdAt: '2026-09-02T09:15:00Z' },
  { id: '00000000-0000-0000-0000-0000000000b2', legalName: 'Namib Fresh Produce (Pty) Ltd', subType: 'MANUFACTURER', type: 'TENANT', status: 'ACTIVE', verificationStatus: 'CASE', createdAt: '2026-10-30T13:40:00Z' },
  { id: '00000000-0000-0000-0000-0000000000b3', legalName: 'Coastal Distributors CC', subType: 'DISTRIBUTOR', type: 'TENANT', status: 'PENDING', verificationStatus: 'TENANT_PENDING', createdAt: '2026-11-01T08:00:00Z' },
  { id: '00000000-0000-0000-0000-0000000000b4', legalName: 'North Wholesalers', subType: 'WHOLESALER', type: 'TENANT', status: 'ACTIVE', verificationStatus: 'APPROVED', createdAt: '2026-10-10T11:20:00Z' },
  { id: '00000000-0000-0000-0000-0000000000b5', legalName: 'Kalahari Trading Co', subType: 'MERCHANT', type: 'TENANT', status: 'DISABLED', verificationStatus: 'REJECTED', createdAt: '2026-09-15T16:50:00Z' },
  { id: '00000000-0000-0000-0000-0000000000b6', legalName: 'Etosha Imports (Pty) Ltd', subType: 'DISTRIBUTOR', type: 'TENANT', status: 'ACTIVE', verificationStatus: 'CASE', createdAt: '2026-10-25T09:05:00Z' },
  { id: '00000000-0000-0000-0000-0000000000b7', legalName: 'Fish River Wholesale', subType: 'WHOLESALER', type: 'TENANT', status: 'PENDING', verificationStatus: 'DRAFT', createdAt: '2026-11-05T10:10:00Z' },
  { id: '00000000-0000-0000-0000-0000000000b8', legalName: 'Skeleton Coast Supplies', subType: 'MERCHANT', type: 'TENANT', status: 'ACTIVE', verificationStatus: 'APPROVED', createdAt: '2026-08-20T14:30:00Z' },
  { id: '00000000-0000-0000-0000-0000000000b9', legalName: 'Okavango Payments', subType: 'MANUFACTURER', type: 'TENANT', status: 'PENDING', verificationStatus: 'TENANT_PENDING', createdAt: '2026-10-01T07:45:00Z' },
  { id: '00000000-0000-0000-0000-0000000000ba', legalName: 'Brandberg Merchants', subType: 'MERCHANT', type: 'TENANT', status: 'ACTIVE', verificationStatus: 'APPROVED', createdAt: '2026-09-18T12:00:00Z' },
];

function pageOf(rows: OrgRow[], q: OrgListQuery): OrgPage {
  let set = rows;
  const tq = q.q.trim().toLowerCase();
  if (tq.length >= 2) set = set.filter((r) => (r.legalName ?? '').toLowerCase().includes(tq));
  const [sk, sd = 'asc'] = q.sort.split(',') as [string, string];
  set = [...set].sort((a, b) => {
    const av = (a as unknown as Record<string, string | null>)[sk] ?? '';
    const bv = (b as unknown as Record<string, string | null>)[sk] ?? '';
    const c = String(av ?? '').localeCompare(String(bv ?? ''));
    return sd === 'desc' ? -c : c;
  });
  const from = q.page * q.size;
  const items = set.slice(from, from + q.size);
  return {
    items,
    page: q.page,
    size: q.size,
    hasNext: items.length === q.size && from + q.size < set.length,
    sort: q.sort,
    q: q.q.trim().length >= 2 ? q.q : null,
  };
}

export const fixtures: FixtureSet = {
  'onboard.orgs.list': {
    default: (req: FixtureRequest): OrgPage => {
      forbidUnless(req.permissions, 'org.create');
      const p = req.params;
      const [, sd = 'asc'] = (p.sort ?? DEFAULT_QUERY.sort).split(',') as [string, string];
      const size = Number(p.size);
      const page = Number(p.page);
      const q: OrgListQuery = {
        q: p.q ?? '',
        sort: p.sort ?? DEFAULT_QUERY.sort,
        dir: sd === 'asc' || sd === 'desc' ? sd : DEFAULT_QUERY.dir,
        page: Number.isInteger(page) && page >= 0 ? page : 0,
        size: PAGE_SIZES.includes(size) ? size : DEFAULT_QUERY.size,
      };
      return pageOf(all, q);
    },
    empty: { items: [], page: 0, size: 25, hasNext: false, sort: 'legalName,asc', q: null },
    error: null,
  },
};
