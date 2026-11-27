/**
 * kyc.customers data contributions — customer KYC through the KYC plugin
 * (browser → BFF /api/plugins/kyc → gateway → plugin). The fixtures play the plugin
 * + core rules the widget depends on: four-eyes, activation-before-approval, contact
 * uniqueness, kind=CUSTOMER paging.
 */
import type { DataSources } from '../../core/sources';
import type { FixtureRequest, FixtureSet } from '../../core/ports';
import { ApiError } from '../../../web-core/api';
import { forbidUnless } from '../../kit/forbid';
import type { CustomerCaseView, CustomerRow } from './model';

export const sources: DataSources = {
  'kyc.customers.page': { '*': { method: 'GET', path: '/api/plugins/kyc/cases', query: ['kind', 'page', 'size', 'q', 'status', 'sort'] } },
  'kyc.customers.view': { '*': { method: 'GET', path: '/api/plugins/kyc/cases/{caseId}' } },
};

export const commands: DataSources = {
  'kyc.customers.create': { '*': { method: 'POST', path: '/api/plugins/kyc/customers' } },
  'kyc.customers.resend': { '*': { method: 'POST', path: '/api/plugins/kyc/cases/{caseId}/activation' } },
  'kyc.customers.decide': { '*': { method: 'POST', path: '/api/plugins/kyc/cases/{caseId}/decisions' } },
};

/* ------------------------------------------------------------- fixtures */
const ME = '00000000-0000-0000-0000-00000000f001';
const OTHER = '00000000-0000-0000-0000-00000000f002';

interface Rec {
  caseId: string; caseNo: string; firstName: string; lastName: string; gender: string; dateOfBirth: string;
  email: string | null; phone: string | null; primaryContact: 'EMAIL' | 'PHONE';
  status: 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED'; verifiedAt: string | null;
  maker: string; makerName: string; submittedAt: string; version: number;
  home: Addr; postal: Addr | null;
}
interface Addr { line: string; city: string; region: string; postalCode: string | null }
const WHK: Addr = { line: '12 Robert Mugabe Ave', city: 'Windhoek', region: 'KHOMAS', postalCode: '10001' };

const seed = (): Rec[] => [
  { caseId: 'c0000000-0000-0000-0000-000000000001', caseNo: 'KYC-2026-000101', firstName: 'Ndapewa', lastName: 'Shilongo', gender: 'FEMALE', dateOfBirth: '1991-04-12', email: 'ndapewa@example.com', phone: null, primaryContact: 'EMAIL', status: 'PENDING_REVIEW', verifiedAt: null, maker: OTHER, makerName: 'ops.maker', submittedAt: '2026-11-20T09:10:00Z', version: 2, home: WHK, postal: null },
  { caseId: 'c0000000-0000-0000-0000-000000000002', caseNo: 'KYC-2026-000102', firstName: 'Johannes', lastName: 'van Wyk', gender: 'MALE', dateOfBirth: '1985-09-30', email: null, phone: '+264811234567', primaryContact: 'PHONE', status: 'PENDING_REVIEW', verifiedAt: '2026-11-21T15:02:00Z', maker: OTHER, makerName: 'ops.maker', submittedAt: '2026-11-21T08:45:00Z', version: 3, home: { line: '7 Sam Nujoma Ave', city: 'Ongwediva', region: 'OSHANA', postalCode: null }, postal: { line: 'PO Box 55', city: 'Oshakati', region: 'OSHANA', postalCode: '9000' } },
  { caseId: 'c0000000-0000-0000-0000-000000000003', caseNo: 'KYC-2026-000103', firstName: 'Selma', lastName: 'Nangolo', gender: 'FEMALE', dateOfBirth: '1979-01-05', email: 'selma@example.com', phone: '+264817654321', primaryContact: 'EMAIL', status: 'APPROVED', verifiedAt: '2026-11-18T10:00:00Z', maker: ME, makerName: 'me', submittedAt: '2026-11-17T12:00:00Z', version: 5, home: { line: '3 Hage Geingob St', city: 'Swakopmund', region: 'ERONGO', postalCode: '13001' }, postal: null },
];
let db = seed();

const mask = (v: string | null) => (v && v.length > 4 ? `•••• ${v.slice(-4)}` : v);
const maskEmail = (v: string | null) => (v && v.indexOf('@') > 1 ? `${v[0]}•••${v.slice(v.indexOf('@'))}` : v);

function row(r: Rec): CustomerRow {
  return {
    caseId: r.caseId, caseNo: r.caseNo, legalName: `${r.firstName} ${r.lastName}`, status: r.status,
    submittedAt: r.submittedAt, makerName: r.makerName, primaryContact: r.primaryContact,
    contactVerified: r.verifiedAt !== null, version: r.version,
  };
}

function view(r: Rec, req: FixtureRequest): CustomerCaseView {
  const canApprove = req.permissions.includes('kyc.approve');
  const blockedReason = r.status !== 'PENDING_REVIEW' ? 'STATE' : r.maker === ME ? 'MAKER' : canApprove ? null : 'PERMISSION';
  return {
    ...row(r),
    registeredAddress: r.home.line, registeredCity: r.home.city, registeredRegion: r.home.region,
    registeredPostal: r.home.postalCode, registeredCountry: 'NA', operationalAddressSame: r.postal === null,
    operationalAddress: r.postal?.line ?? null, operationalCity: r.postal?.city ?? null,
    operationalRegion: r.postal?.region ?? null, operationalPostal: r.postal?.postalCode ?? null,
    ownerTenantId: 'own', subjectTenantId: null, subType: null, dbaName: null, entityType: null, registrationNumber: null,
    riskRating: r.status === 'APPROVED' ? 'LOW' : null, nextReviewDate: null, updatedAt: r.submittedAt,
    makerPersonId: r.maker, canDecide: blockedReason === null, blockedReason,
    checks: [{ checkId: `${r.caseId}-s`, checkType: 'SANCTIONS', result: 'PASS', details: 'No watchlist match (sandbox provider)', simulated: true, checkedAt: r.submittedAt }],
    ubos: [], bankAccounts: [], caseKind: 'CUSTOMER',
    customer: {
      personId: `p-${r.caseId}`, firstName: r.firstName, lastName: r.lastName, gender: r.gender, dateOfBirth: r.dateOfBirth,
      email: maskEmail(r.email), phone: mask(r.phone), primaryContact: r.primaryContact,
      contactVerified: r.verifiedAt !== null, contactVerifiedAt: r.verifiedAt, activationSentAt: r.submittedAt,
    },
  } as unknown as CustomerCaseView;
}

const problem = (status: number, code: string, detail: string) => new ApiError({ status, code, title: code, detail });

function find(id: string | undefined): Rec {
  const r = db.find((x) => x.caseId === id);
  if (!r) throw problem(404, 'KYC_CASE_NOT_FOUND', 'Case not found or not accessible');
  return r;
}

function page(req: FixtureRequest) {
  forbidUnless(req.permissions, 'kyc.review');
  const p = req.params;
  const size = Number(p.size) || 25;
  const pg = Number(p.page) || 0;
  const q = (p.q ?? '').toLowerCase();
  let set = db.filter((r) => (!p.status || r.status === p.status)
    && (q.length < 2 || `${r.firstName} ${r.lastName} ${r.caseNo} ${r.email ?? ''}`.toLowerCase().includes(q)));
  const [sk, sd = 'desc'] = (p.sort ?? 'submittedAt,desc').split(',');
  set = [...set].sort((a, b) => {
    const av = sk === 'legalName' ? `${a.firstName} ${a.lastName}` : sk === 'status' ? a.status : a.submittedAt;
    const bv = sk === 'legalName' ? `${b.firstName} ${b.lastName}` : sk === 'status' ? b.status : b.submittedAt;
    return sd === 'asc' ? av.localeCompare(bv) : bv.localeCompare(av);
  });
  const items = set.slice(pg * size, pg * size + size).map(row);
  return { items, page: pg, size, hasNext: (pg + 1) * size < set.length, nextAfter: null, sort: `${sk},${sd}`, q: q || null };
}

function create(req: FixtureRequest) {
  forbidUnless(req.permissions, 'org.onboard_child');
  const b = req.body as Record<string, string | null> & { residentialAddress?: Addr | null; postalSameAsResidential?: boolean; postalAddress?: Addr | null };
  if (!b.residentialAddress?.line || !b.residentialAddress.city || !b.residentialAddress.region) {
    throw problem(400, 'KYC_VALIDATION', 'Enter the residential street address');
  }
  if (b.email && db.some((r) => r.email === b.email && r.status !== 'REJECTED')) {
    throw problem(409, 'CUSTOMER_EMAIL_TAKEN', 'Email is already registered.');
  }
  if (b.phone && db.some((r) => r.phone === b.phone && r.status !== 'REJECTED')) {
    throw problem(409, 'CUSTOMER_PHONE_TAKEN', 'Phone number is already registered.');
  }
  const n = db.length + 101;
  const r: Rec = {
    caseId: `c0000000-0000-0000-0000-${String(n).padStart(12, '0')}`, caseNo: `KYC-2026-000${n}`,
    firstName: String(b.firstName), lastName: String(b.lastName), gender: String(b.gender), dateOfBirth: String(b.dateOfBirth),
    email: b.email ?? null, phone: b.phone ?? null, primaryContact: b.primaryContact === 'PHONE' ? 'PHONE' : 'EMAIL',
    status: 'PENDING_REVIEW', verifiedAt: null, maker: ME, makerName: 'me', submittedAt: new Date().toISOString(), version: 1,
    home: b.residentialAddress, postal: b.postalSameAsResidential === false ? b.postalAddress ?? null : null,
  };
  db = [r, ...db];
  return view(r, req);
}

function resend(req: FixtureRequest) {
  forbidUnless(req.permissions, 'org.onboard_child');
  const r = find(req.params.caseId);
  if (r.verifiedAt) throw problem(409, 'ALREADY_ACTIVATED', 'This person already set their password');
  return { caseId: r.caseId, channels: [r.primaryContact] };
}

function decide(req: FixtureRequest) {
  const b = req.body as { decision: string; reason: string | null; expectedVersion: number };
  forbidUnless(req.permissions, b.decision === 'REJECT' ? 'kyc.reject' : 'kyc.approve');
  const r = find(req.params.caseId);
  if (r.status !== 'PENDING_REVIEW') throw problem(409, 'KYC_STATE', 'Case is not PENDING_REVIEW');
  if (b.expectedVersion !== r.version) throw problem(409, 'STALE_WRITE', 'Case version mismatch');
  if (r.maker === ME) throw problem(403, 'MAKER_CANNOT_APPROVE', 'Checker cannot be the maker (four-eyes at person_id)');
  if (b.decision === 'APPROVE' && !r.verifiedAt) {
    throw problem(409, 'CONTACT_NOT_VERIFIED', 'The customer has not activated yet — they must set their password before you can activate them');
  }
  r.status = b.decision === 'APPROVE' ? 'APPROVED' : 'REJECTED';
  r.version += 1;
  return view(r, req);
}

const emptyPage = { items: [], page: 0, size: 25, hasNext: false, nextAfter: null, sort: 'submittedAt,desc', q: null };

export const fixtures: FixtureSet = {
  'kyc.customers.page': { default: page, empty: emptyPage, error: null },
  'kyc.customers.view': { default: (req: FixtureRequest) => (forbidUnless(req.permissions, 'kyc.review'), view(find(req.params.caseId), req)) },
  'kyc.customers.create': { default: create },
  'kyc.customers.resend': { default: resend },
  'kyc.customers.decide': { default: decide },
};

/** tests: start from the seed again */
export function resetCustomerFixtures() {
  db = seed();
}
