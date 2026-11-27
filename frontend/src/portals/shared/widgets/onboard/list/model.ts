/**
 * onboard.list — organizations for onboarding (platform root): a paginated,
 * server-side searchable + sortable directory showing each org's KYC standing.
 * Each row opens that organization's KYC case. Paging/search/sorting are always
 * the SERVER's (the widget never filters a page) — same contract as kyc.manage.
 */
import { useQuery } from '@tanstack/react-query';
import type { WidgetDataPortLike } from '../../core/manifest';
import { toProblem } from '../../kit/problem';
import type { Problem } from '../../kit/problem';

export interface OrgRow {
  id: string;
  legalName: string | null;
  subType: string | null;
  type: string | null;
  status: string;
  verificationStatus: string | null;
  createdAt: string | null;
}

/** Minimal KYC case info matched to an org (from kyc.cases.page / kyc.case.view). */
export interface KycCaseSummary {
  caseId: string;
  caseNo: string;
  subjectTenantId: string | null;
  legalName: string;
  status: string;
  riskRating: string | null;
  submittedAt: string | null;
  updatedAt: string;
}

/** An organization row joined with its KYC case (if any). */
export interface OrgWithKyc extends OrgRow {
  kycCaseId: string | null;
  kycCaseNo: string | null;
  kycCaseStatus: string | null;
  kycRiskRating: string | null;
  kycSubmittedAt: string | null;
  hasKyc: boolean;
}

export interface OrgPage {
  items: OrgRow[];
  page: number;
  size: number;
  hasNext: boolean;
  sort: string;
  q: string | null;
}

export interface OrgListQuery {
  q: string;
  sort: string;   // 'legalName,asc' | 'createdAt,desc' | ...
  dir: 'asc' | 'desc';
  page: number;
  size: number;
}

export const DEFAULT_QUERY: OrgListQuery = { q: '', sort: 'legalName', dir: 'asc', page: 0, size: 25 };
export const PAGE_SIZES: number[] = [10, 25, 50, 100];

/** URL → org list query; unknown values are dropped (never forwarded to the server). */
export function parseOrgQuery(p: URLSearchParams): OrgListQuery {
  const [sk, sd] = (p.get('osort') ?? '').split(',');
  const size = Number(p.get('osize'));
  const page = Number(p.get('opage'));
  return {
    q: p.get('oq') ?? '',
    sort: sk ? sk : DEFAULT_QUERY.sort,
    dir: sd === 'asc' || sd === 'desc' ? sd : DEFAULT_QUERY.dir,
    page: Number.isInteger(page) && page >= 0 ? page : 0,
    size: (PAGE_SIZES as readonly number[]).includes(size) ? size : DEFAULT_QUERY.size,
  };
}

/** Org list query → the source's allow-listed params (sources.ts onboard.orgs.list). */
export function orgListParams(q: OrgListQuery): Record<string, string> {
  const out: Record<string, string> = { page: String(q.page), size: String(q.size), sort: `${q.sort},${q.dir}` };
  if (q.q.trim().length >= 2) out.q = q.q.trim();
  return out;
}

export const orgKeys = {
  all: ['onboard', 'orgs'] as const,
  list: (q: OrgListQuery, orgId: string | null) => ['onboard', 'orgs', 'list', orgId ?? '', q] as const,
};

/** One page of organizations, server-side. */
export function useOrgPage(port: WidgetDataPortLike, q: OrgListQuery, orgId: string | null) {
  return useQuery({
    queryKey: orgKeys.list(q, orgId),
    queryFn: () => port.request<OrgPage>('onboard.orgs.list', { tenantId: orgId ?? '', ...orgListParams(q) }),
    enabled: orgId !== '',
    placeholderData: (prev) => prev,
  });
}

/** KYC case list query → server-side page for matching orgs to their cases. */
export interface KycListQuery {
  q: string;
  status: string[];
  sort: string;
  dir: 'asc' | 'desc';
  page: number;
  size: number;
}

export const KYC_DEFAULT_QUERY: KycListQuery = {
  q: '', status: [], sort: 'submittedAt', dir: 'desc', page: 0, size: 100,
};

export function kycListParams(q: KycListQuery): Record<string, string> {
  const out: Record<string, string> = { page: String(q.page), size: String(q.size), sort: `${q.sort},${q.dir}` };
  if (q.q.trim().length >= 2) out.q = q.q.trim();
  if (q.status.length) out.status = q.status.join(',');
  return out;
}

export const kycKeys = {
  all: ['kyc', 'cases'] as const,
  list: (q: KycListQuery, orgId: string | null) => ['kyc', 'cases', 'list', orgId ?? '', q] as const,
};

/** One page of KYC cases, server-side. */
export function useKycCasePage(port: WidgetDataPortLike, q: KycListQuery, orgId: string | null) {
  return useQuery({
    queryKey: kycKeys.list(q, orgId),
    queryFn: () => port.request<{ items: KycCaseSummary[]; page: number; size: number; hasNext: boolean }>(
      'kyc.cases.page',
      { tenantId: orgId ?? '', ...kycListParams(q) },
    ),
    enabled: orgId !== '',
    placeholderData: (prev) => prev,
  });
}

/**
 * Join orgs with their KYC cases by subjectTenantId → org.id.
 * Orgs without a KYC case show hasKyc = false.
 */
export function joinOrgsToKyc(orgs: OrgRow[], cases: KycCaseSummary[]): OrgWithKyc[] {
  const byTenant = new Map(cases.map((c) => [c.subjectTenantId, c]));
  return orgs.map((o) => {
    const kyc = byTenant.get(o.id);
    return {
      ...o,
      kycCaseId: kyc?.caseId ?? null,
      kycCaseNo: kyc?.caseNo ?? null,
      kycCaseStatus: kyc?.status ?? null,
      kycRiskRating: kyc?.riskRating ?? null,
      kycSubmittedAt: kyc?.submittedAt ?? null,
      hasKyc: !!kyc,
    };
  });
}

/** Convert a port error to a stable problem (never echoes tokens/PII). */
export function orgProblem(err: unknown): Problem {
  return toProblem(err);
}
