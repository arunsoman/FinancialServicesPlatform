/**
 * kyc.manage fixture server — plays the KYC plugin API (plans/plugin-platform-plan.md §12.4)
 * for the widget workbench: server-side paging (totals-free), search, multi-filter and the
 * pinned sort map; four-eyes (`canDecide`, 403 MAKER_CANNOT_APPROVE); state preconditions
 * (409 KYC_STATE) and optimistic versions (409 STALE_WRITE); ID / account numbers masked.
 * Shapes and permission keys follow the real plugin (backend/plugins/kyc KycDtos, core V2 seed):
 * view kyc.review · onboard/edit/submit org.onboard_child · approve/request-info kyc.approve ·
 * reject kyc.reject. The caller is always FIXTURE_ME; the in-memory store is shared by frames.
 */
import { ApiError } from '../../../web-core/api';
import type { FixtureRequest, FixtureSet } from '../../core/ports';
import type {
  BankAccountView, CaseStatus, CaseView, CaseWireBody, CheckView, Decision, Page, Risk, WireMasterValue,
  SortKey, SubType, UboView,
} from './model';
import { CASE_STATUSES, MASK, RISKS, SORT_KEYS } from './model';

export const FIXTURE_ME = { id: '00000000-0000-0000-0000-0000000002d1', name: 'Anna Shikongo', isChecker: true };
const OTHERS = [
  { id: '00000000-0000-0000-0000-0000000002d2', name: 'Johannes Kapuka' },
  { id: '00000000-0000-0000-0000-0000000002d3', name: 'Maria Nghipondoka' },
  { id: '00000000-0000-0000-0000-0000000002d4', name: 'Peter Amutenya' },
];

/** internal event (rendered to the plugin's {id, eventType, payload, createdAt}) */
interface Ev {
  id: string;
  at: string;
  type: string;
  actorName: string;
  note: string | null;
}

interface Row extends Omit<CaseView, 'canDecide' | 'blockedReason' | 'canEdit' | 'piiMasked' | 'stalled'> {
  makerId: string;
  makerName: string;
  history: Ev[];
}

/* ------------------------------------------------------------ master data */
const MASTER: WireMasterValue[] = [
  ...[['CC', 'Close corporation (CC)'], ['PTY', 'Private company (Pty) Ltd'], ['LTD', 'Public company Ltd'],
    ['TRUST', 'Trust'], ['SOLE', 'Sole proprietor'], ['PARTNERSHIP', 'Partnership']]
    .map(([code, label]) => ({ category: 'ENTITY_TYPE', code, label })),
  ...[['NATIONAL_ID', 'Namibian ID'], ['PASSPORT', 'Passport'], ['PERMIT', 'Residence permit']]
    .map(([code, label]) => ({ category: 'DOC_TYPE', code, label })),
  ...[['CURRENT', 'Current / cheque'], ['SAVINGS', 'Savings'], ['TRANSMISSION', 'Transmission']]
    .map(([code, label]) => ({ category: 'ACCOUNT_TYPE', code, label })),
  ...[['BWK', 'Bank Windhoek'], ['FNB', 'FNB Namibia'], ['NED', 'Nedbank Namibia'], ['SBN', 'Standard Bank Namibia'],
    ['LEB', 'Letshego Bank']].map(([code, label]) => ({ category: 'BANK', code, label })),
  ...['Khomas', 'Erongo', 'Oshana', 'Otjozondjupa', 'Hardap', 'Karas', 'Kavango East', 'Zambezi', 'Ohangwena', 'Omusati']
    .map((r) => ({ category: 'REGION', code: r.toUpperCase().replace(/\s/g, '_'), label: r })),
  ...[['5411', '5411 · Grocery stores'], ['5499', '5499 · Food stores'], ['5814', '5814 · Fast food'],
    ['5912', '5912 · Pharmacies'], ['5251', '5251 · Hardware'], ['5013', '5013 · Vehicle supplies'],
    ['5122', '5122 · Drugs & sundries (wholesale)'], ['5141', '5141 · Groceries (wholesale)']]
    .map(([code, label]) => ({ category: 'MCC', code, label })),
  ...[['NA', 'Namibian'], ['ZA', 'South African'], ['BW', 'Motswana'], ['AO', 'Angolan'], ['DE', 'German']]
    .map(([code, label]) => ({ category: 'COUNTRY', code, label })),
];

/* -------------------------------------------------------- seeded dataset */
const NAMES = [
  'Namib Fresh Produce', 'Kalahari Hardware', 'Etosha Pharmacy', 'Swakop Coffee Roasters', 'Okavango Foods',
  'Brandberg Builders Supply', 'Caprivi Grain Traders', 'Windhoek Auto Parts', 'Lüderitz Seafood', 'Rundu Wholesale',
  'Oshakati Cash & Carry', 'Sossusvlei Bakery', 'Erongo Mining Supplies', 'Khomas Medical', 'Otavi Meat Market',
  'Keetmans Farm Stores', 'Walvis Cold Chain', 'Ondangwa Beverages', 'Gobabis Livestock Feeds', 'Tsumeb Tyres',
  'Katima Fisheries', 'Mariental Dairy', 'Opuwo Crafts', 'Henties Bay Hospitality', 'Grootfontein Agri',
  'Outjo Butchery', 'Okahandja Timber', 'Aranos Solar', 'Eenhana General Dealer', 'Omaruru Wines',
  'Usakos Transport', 'Karibib Marble Works', 'Rehoboth Textiles', 'Oranjemund Diamonds Retail', 'Nkurenkuru Traders',
  'Otjiwarongo Vet Supplies', 'Arandis Industrial', 'Okakarara Feeds', 'Bethanie Stores', 'Divundu Fresh',
  'Kamanjab Fuel', 'Tses Water Works',
];
const SUBS: SubType[] = ['MERCHANT', 'DISTRIBUTOR', 'WHOLESALER', 'DEALER', 'MANUFACTURER'];
const ENTITY = ['CC', 'PTY', 'PTY', 'CC', 'TRUST', 'SOLE'];
const STATUS_CYCLE: CaseStatus[] = [
  'PENDING_REVIEW', 'APPROVED', 'PENDING_REVIEW', 'REQUEST_INFO', 'APPROVED', 'REJECTED', 'PENDING_REVIEW',
  'DRAFT', 'APPROVED', 'SUBMITTED', 'APPROVED', 'TENANT_PENDING', 'PENDING_REVIEW', 'STALLED',
];
const RISK_CYCLE: Risk[] = ['LOW', 'MEDIUM', 'LOW', 'HIGH', 'LOW', 'MEDIUM'];
const BASE = Date.UTC(2026, 10, 18, 9, 0, 0);
const iso = (ms: number) => new Date(ms).toISOString();
const day = 86_400_000;
const hex = (n: number, w = 12) => n.toString(16).padStart(w, '0');

function personFor(i: number) {
  // every 5th open case is the caller's own submission — the four-eyes block shows up
  return i % 5 === 2 ? FIXTURE_ME : OTHERS[i % OTHERS.length];
}

function seed(i: number): Row {
  const name = NAMES[i];
  const status = STATUS_CYCLE[i % STATUS_CYCLE.length];
  const maker = personFor(i);
  const sub = SUBS[i % SUBS.length];
  const entity = ENTITY[i % ENTITY.length];
  const submittedAt = status === 'DRAFT' ? null : iso(BASE - (i * 37 + 3) * 3_600_000);
  const updatedAt = iso(BASE - (i * 11 + 1) * 3_600_000);
  const risk: Risk | null = status === 'DRAFT' || status === 'TENANT_PENDING' || status === 'STALLED' ? null : RISK_CYCLE[i % RISK_CYCLE.length];
  const decided = status === 'APPROVED' || status === 'REJECTED' || status === 'REQUEST_INFO';
  const checker = OTHERS[(i + 1) % OTHERS.length];
  const caseId = `00000000-0000-4000-8000-${hex(0xc000 + i)}`;
  // approved cases: every 4th review is overdue, the rest fall due over the coming months
  const nextReviewDate = status === 'APPROVED' ? iso(BASE + (i % 4 === 0 ? -12 : 20 + i * 9) * day) : null;
  const regNo = `${entity === 'CC' ? 'CC' : ''}${2010 + (i % 15)}${String(1000 + i * 37).slice(-4)}`;
  const checks: CheckView[] = submittedAt
    ? [
        { checkId: `chk-${i}-1`, checkType: 'SANCTIONS', result: 'PASS', details: 'No match on UN / OFAC / EU lists', simulated: true, checkedAt: submittedAt },
        { checkId: `chk-${i}-2`, checkType: 'PEP', result: i % 6 === 3 ? 'MANUAL_REVIEW' : 'PASS', details: i % 6 === 3 ? 'Owner matches a domestic PEP record — review required' : 'No politically exposed owners', simulated: true, checkedAt: submittedAt },
        { checkId: `chk-${i}-3`, checkType: 'BIPA_REGISTRY', result: status === 'REJECTED' ? 'FAIL' : 'PASS', details: status === 'REJECTED' ? 'Registration number not found at BIPA' : 'Registration confirmed', simulated: true, checkedAt: submittedAt },
      ]
    : [];
  const ubos: UboView[] = [
    { id: `ubo-${i}-1`, fullName: OTHERS[(i + 2) % 3].name.replace(/\w+$/, NAMES[(i + 7) % NAMES.length].split(' ')[0]), dob: `19${70 + (i % 25)}-0${1 + (i % 9)}-1${i % 9}`, nationality: i % 7 === 4 ? 'ZA' : 'NA', idDocType: i % 7 === 4 ? 'PASSPORT' : 'NATIONAL_ID', idNumber: `${7_000_000_000 + i * 7919}`, ownershipPct: i % 3 === 0 ? '100' : '60', isPep: i % 6 === 3 },
    ...(i % 3 === 0 ? [] : [{ id: `ubo-${i}-2`, fullName: `Selma ${NAMES[(i + 3) % NAMES.length].split(' ')[0]}`, dob: '1984-05-21', nationality: 'NA', idDocType: 'NATIONAL_ID', idNumber: `${8_400_000_000 + i * 104_729}`, ownershipPct: '40', isPep: false }]),
  ];
  const bank = ['BWK', 'FNB', 'NED', 'SBN'][i % 4];
  const bankAccounts: BankAccountView[] = [
    { id: `ba-${i}-1`, holderName: name, bankName: bank, branchCode: `48${String(1000 + i * 13).slice(-4)}`, accountNumber: `${62_000_000_000 + i * 1_000_003}`, accountType: 'CURRENT', status: status === 'APPROVED' ? 'VERIFIED' : 'PENDING' },
  ];
  const history: Ev[] = [];
  const ev = (type: string, at: string, actorName: string, note: string | null = null) =>
    history.push({ id: `ev-${i}-${history.length + 1}`, at, type, actorName, note });
  ev('CREATED', iso(BASE - (i * 37 + 5) * 3_600_000), maker.name);
  if (submittedAt) {
    ev('TENANT_CREATED', submittedAt, 'KYC plugin', 'Organization created in core, pending verification');
    if (status !== 'TENANT_PENDING') ev('SCREENED', submittedAt, 'KYC plugin', 'Sanctions, PEP and BIPA registry checks');
    if (!['TENANT_PENDING', 'SUBMITTED', 'STALLED'].includes(status)) ev('SUBMITTED', submittedAt, maker.name, 'Approval opened for a second reviewer');
  }
  const reason = status === 'REJECTED' ? 'Registration number could not be verified at BIPA; the applicant did not respond within 30 days.'
    : status === 'REQUEST_INFO' ? 'Please upload a certified copy of the founding statement and confirm the second owner’s ID.' : null;
  if (decided) ev(status === 'APPROVED' ? 'APPROVED' : status === 'REJECTED' ? 'REJECTED' : 'REQUEST_INFO', updatedAt, checker.name, reason);

  return {
    caseId, caseNo: `KYC-2026-${String(120 + i).padStart(6, '0')}`,
    subjectTenantId: status === 'DRAFT' ? null : `00000000-0000-0000-0000-${hex(0x7000 + i)}`,
    legalName: `${name} ${entity === 'CC' ? 'CC' : entity === 'TRUST' ? 'Trust' : entity === 'SOLE' ? '' : '(Pty) Ltd'}`.trim(),
    dbaName: i % 4 === 1 ? name.split(' ')[0] : null,
    subType: sub, registrationNumber: regNo, status, riskRating: risk, submittedAt,
    nextReviewDate, makerName: maker.name, makerId: maker.id, updatedAt, version: decided ? 3 : 1,
    entityType: entity, incorporationDate: `${2010 + (i % 15)}-0${1 + (i % 9)}-15`, taxId: String(10_000_000 + i * 104_723).slice(0, 8),
    mccCode: ['5411', '5499', '5251', '5912', '5141', '5013'][i % 6], websiteUrl: i % 3 === 1 ? `https://${name.split(' ')[0].toLowerCase()}.com.na` : null,
    expectedMonthlyVolume: String((150_000 + i * 42_500) * 100), avgTicketSize: String((350 + i * 25) * 100), currency: 'NAD',
    registeredAddress: `${10 + i} ${['Independence Ave', 'Sam Nujoma Dr', 'Hosea Kutako St', 'Mandume Ndemufayo Ave'][i % 4]}`,
    registeredCity: ['Windhoek', 'Swakopmund', 'Oshakati', 'Walvis Bay', 'Rundu'][i % 5],
    registeredRegion: ['KHOMAS', 'ERONGO', 'OSHANA', 'ERONGO', 'KAVANGO_EAST'][i % 5], registeredPostal: i % 2 ? '9000' : null,
    registeredCountry: 'NA', operationalAddressSame: i % 5 !== 3,
    operationalAddress: i % 5 === 3 ? `Erf ${300 + i}, Industrial Area` : null, operationalCity: i % 5 === 3 ? 'Okahandja' : null,
    operationalRegion: i % 5 === 3 ? 'OTJOZONDJUPA' : null, operationalPostal: null,
    ubos, bankAccounts, checks,
    approval: submittedAt && !['TENANT_PENDING', 'SUBMITTED', 'STALLED'].includes(status)
      ? { approvalId: `00000000-0000-0000-0000-${hex(0xa000 + i)}`, rev: status === 'PENDING_REVIEW' && i % 7 === 0 ? 2 : 1, payloadHash: `sha256:${hex(i * 2_654_435_761 + 97, 16)}${hex(i * 40_503 + 1, 16)}`, makerName: maker.name, submittedAt }
      : null,
    decision: decided ? { decision: status === 'APPROVED' ? 'APPROVE' : status === 'REJECTED' ? 'REJECT' : 'REQUEST_INFO', reason, checkerName: checker.name, decidedAt: updatedAt } : null,
    history,
  };
}

let STORE: Row[] = NAMES.map((_, i) => seed(i));
let NEXT_NO = 120 + NAMES.length;

/** Reset (tests / a fresh workbench session). */
export function resetKycFixtures() {
  STORE = NAMES.map((_, i) => seed(i));
  NEXT_NO = 120 + NAMES.length;
}

/* ---------------------------------------------------------------- problems */
const problem = (status: number, code: string, detail: string) => new ApiError({ status, code, title: code, detail });
const need = (req: FixtureRequest, key: string) => {
  if (!req.permissions.includes(key)) throw problem(403, 'PERMISSION_DENIED', `Requires the ${key} permission on this organization.`);
};

/* ------------------------------------------------------------------- views */
const STALL_MS = 10 * 60_000;
const isStalled = (r: Row) =>
  r.status === 'STALLED' ||
  ((r.status === 'TENANT_PENDING' || r.status === 'SUBMITTED') && Date.now() - new Date(r.updatedAt).getTime() > STALL_MS);
const mask = (v: string) => `${MASK} ${v.slice(-4)}`;

function summary(r: Row) {
  return {
    caseId: r.caseId, caseNo: r.caseNo, subjectTenantId: r.subjectTenantId, legalName: r.legalName, dbaName: r.dbaName,
    subType: r.subType, registrationNumber: r.registrationNumber, status: r.status, riskRating: r.riskRating,
    submittedAt: r.submittedAt, nextReviewDate: r.nextReviewDate, makerName: r.makerName, updatedAt: r.updatedAt,
    version: r.version, stalled: isStalled(r),
  };
}

function view(r: Row, req: FixtureRequest): CaseView {
  const pii = false; // the plugin masks; no PII-read permission exists in core yet
  const mine = r.makerId === FIXTURE_ME.id;
  const open = r.status === 'PENDING_REVIEW';
  // the plugin sends a sentence for the maker block (KycCaseService.get) — mirror it
  const blockedReason = !open ? 'STATE'
    : mine ? 'You submitted this application — another reviewer must decide (four-eyes)'
    : !req.permissions.includes('kyc.approve') ? 'PERMISSION'
    : !FIXTURE_ME.isChecker ? 'CHECKER' : null;
  const rest: Partial<Row> = { ...r };
  delete rest.makerId;
  delete rest.history;
  return {
    ...(rest as Omit<Row, 'makerId' | 'history'>),
    ...summary(r),
    ubos: r.ubos.map((u) => (pii ? u : { ...u, idNumber: mask(u.idNumber) })),
    bankAccounts: r.bankAccounts.map((b) => (pii ? b : { ...b, accountNumber: mask(b.accountNumber) })),
    canDecide: blockedReason === null,
    blockedReason,
    piiMasked: !pii,
  };
}

const find = (id: string | undefined) => {
  const r = STORE.find((x) => x.caseId === id);
  if (!r) throw problem(404, 'KYC_CASE_NOT_FOUND', 'No KYC case with that id in your organizations.');
  return r;
};

/* -------------------------------------------------------------------- list */
const SORT_EXPR: Record<SortKey, (r: Row) => string> = {
  submittedAt: (r) => r.submittedAt ?? '',
  legalName: (r) => r.legalName.toLowerCase(),
  status: (r) => r.status,
  riskRating: (r) => String(r.riskRating ? RISKS.indexOf(r.riskRating) : -1),
  nextReviewDate: (r) => r.nextReviewDate ?? '',
  updatedAt: (r) => r.updatedAt,
};

function enumList<T extends string>(raw: string | undefined, set: readonly T[], name: string): T[] {
  if (!raw) return [];
  const out = raw.split(',');
  for (const v of out) if (!(set as readonly string[]).includes(v)) throw problem(400, 'VALIDATION', `Unknown ${name} "${v}".`);
  return out as T[];
}

function list(req: FixtureRequest): Page<ReturnType<typeof summary>> {
  need(req, 'kyc.review');
  const p = req.params;
  const page = Number(p.page ?? 0);
  const size = Number(p.size ?? 25);
  if (!Number.isInteger(page) || page < 0) throw problem(400, 'VALIDATION', 'page must be ≥ 0.');
  if (!Number.isInteger(size) || size < 1 || size > 100) throw problem(400, 'VALIDATION', 'size must be 1–100.');
  const q = (p.q ?? '').trim();
  if (q && (q.length < 2 || q.length > 100)) throw problem(400, 'VALIDATION', 'Search needs 2–100 characters.');
  const status = enumList(p.status, CASE_STATUSES, 'status');
  const risk = enumList(p.risk, RISKS, 'risk');
  const [sk = 'submittedAt', sd = 'desc'] = (p.sort ?? '').split(',');
  if (!(SORT_KEYS as readonly string[]).includes(sk) || !['asc', 'desc'].includes(sd)) {
    throw problem(400, 'VALIDATION', `Unknown sort "${p.sort}".`);
  }
  const needle = q.toLowerCase();
  const rows = STORE.filter(
    (r) =>
      (!status.length || status.includes(r.status)) &&
      (!risk.length || (r.riskRating !== null && risk.includes(r.riskRating))) &&
      (!needle ||
        [r.legalName, r.dbaName ?? '', r.registrationNumber ?? '', r.taxId, r.caseNo].some((f) => f.toLowerCase().includes(needle))),
  );
  const key = SORT_EXPR[sk as SortKey];
  const dir = sd === 'asc' ? 1 : -1;
  rows.sort((a, b) => {
    const c = key(a).localeCompare(key(b));
    return c !== 0 ? c * dir : a.caseId.localeCompare(b.caseId); // pinned tiebreak
  });
  const slice = rows.slice(page * size, page * size + size + 1);
  const hasNext = slice.length > size;
  return {
    items: slice.slice(0, size).map(summary), page, size, hasNext, nextAfter: hasNext ? page + 1 : null,
    sort: `${sk},${sd}`, q: q || null,
  };
}

/* ------------------------------------------------------------------ writes */
function checkVersion(r: Row, body: unknown) {
  const v = (body as { expectedVersion?: number } | null)?.expectedVersion;
  if (v !== r.version) throw problem(409, 'STALE_WRITE', 'Someone changed this case since you opened it. Reload to see the latest.');
}

function applyBody(r: Row, b: CaseWireBody) {
  const keep = <T extends { id: string }>(old: T[], id: string | null) => old.find((o) => o.id === id);
  Object.assign(r, {
    subType: b.subType as SubType, legalName: b.legalName, dbaName: b.dbaName, entityType: b.entityType,
    registrationNumber: b.registrationNumber, incorporationDate: b.incorporationDate, taxId: b.taxId,
    mccCode: b.mccCode, websiteUrl: b.websiteUrl, expectedMonthlyVolume: b.expectedMonthlyVolume, avgTicketSize: b.avgTicketSize,
    registeredAddress: b.registeredAddress, registeredCity: b.registeredCity, registeredRegion: b.registeredRegion,
    registeredPostal: b.registeredPostal, operationalAddressSame: b.operationalAddressSame,
    operationalAddress: b.operationalAddress, operationalCity: b.operationalCity, operationalRegion: b.operationalRegion,
    operationalPostal: b.operationalPostal,
    ubos: b.ubos.map((u, n) => ({
      ...u, id: u.id ?? `ubo-${r.caseNo}-${n}-${Date.now()}`,
      idNumber: u.idNumber ?? keep(r.ubos, u.id)?.idNumber ?? '',
    })),
    bankAccounts: b.bankAccounts.map((a, n) => ({
      ...a, id: a.id ?? `ba-${r.caseNo}-${n}-${Date.now()}`, status: 'PENDING',
      accountNumber: a.accountNumber ?? keep(r.bankAccounts, a.id)?.accountNumber ?? '',
    })),
  });
}

function validateBody(b: CaseWireBody, self: string | null) {
  if (STORE.some((r) => r.registrationNumber === b.registrationNumber && r.caseId !== self && r.status !== 'REJECTED')) {
    throw problem(409, 'REGISTRATION_TAKEN', 'An open or approved case already uses this registration number.');
  }
  if (b.ubos.some((u) => Number(u.ownershipPct) < 25)) {
    throw problem(400, 'UBO_THRESHOLD', 'Beneficial owners are people holding 25% or more.');
  }
}

function openReview(r: Row, now: string) {
  r.status = 'PENDING_REVIEW';
  r.submittedAt = now;
  r.makerId = FIXTURE_ME.id;
  r.makerName = FIXTURE_ME.name;
  const rev = (r.approval?.rev ?? 0) + 1;
  r.approval = {
    approvalId: r.approval?.approvalId ?? `00000000-0000-0000-0000-${hex(Date.now() % 0xffffffffffff)}`,
    rev, payloadHash: `sha256:${hex(Date.now() * 2_654_435_761 % 2 ** 48, 16)}${hex(rev * 977, 16)}`,
    makerName: FIXTURE_ME.name, submittedAt: now,
  };
  r.decision = null;
}

function create(req: FixtureRequest): CaseView {
  need(req, 'org.onboard_child');
  const b = req.body as CaseWireBody;
  validateBody(b, null);
  const now = new Date().toISOString();
  const n = NEXT_NO++;
  const caseId = `00000000-0000-4000-8000-${hex(0xd000 + n)}`;
  const r: Row = {
    ...seed(0), caseId, caseNo: `KYC-2026-${String(n).padStart(6, '0')}`,
    subjectTenantId: `00000000-0000-0000-0000-${hex(0x8000 + n)}`, riskRating: 'MEDIUM', nextReviewDate: null,
    updatedAt: now, version: 1, approval: null, decision: null, history: [],
    checks: [
      { checkId: `chk-${n}-1`, checkType: 'SANCTIONS', result: 'PASS', details: 'No match on UN / OFAC / EU lists', simulated: true, checkedAt: now },
      { checkId: `chk-${n}-2`, checkType: 'PEP', result: b.ubos.some((u) => u.isPep) ? 'MANUAL_REVIEW' : 'PASS', details: b.ubos.some((u) => u.isPep) ? 'An owner is a declared PEP — review required' : 'No politically exposed owners', simulated: true, checkedAt: now },
      { checkId: `chk-${n}-3`, checkType: 'BIPA_REGISTRY', result: 'PASS', details: 'Registration confirmed', simulated: true, checkedAt: now },
    ],
  };
  applyBody(r, b);
  if (r.checks.some((c) => c.result !== 'PASS')) r.riskRating = 'HIGH';
  openReview(r, now);
  r.history.push(
    { id: `ev-${n}-1`, at: now, type: 'CREATED', actorName: FIXTURE_ME.name, note: null },
    { id: `ev-${n}-2`, at: now, type: 'TENANT_CREATED', actorName: 'KYC plugin', note: 'Organization created in core, pending verification' },
    { id: `ev-${n}-3`, at: now, type: 'SCREENED', actorName: 'KYC plugin', note: 'Sanctions, PEP and BIPA registry checks' },
    { id: `ev-${n}-4`, at: now, type: 'SUBMITTED', actorName: FIXTURE_ME.name, note: 'Approval opened for a second reviewer' },
  );
  STORE.unshift(r);
  return view(r, req);
}

function update(req: FixtureRequest): CaseView {
  need(req, 'org.onboard_child');
  const r = find(req.params.caseId);
  if (r.status !== 'DRAFT' && r.status !== 'REQUEST_INFO') {
    throw problem(409, 'KYC_STATE', `A ${r.status.toLowerCase().replace(/_/g, ' ')} case can’t be edited.`);
  }
  checkVersion(r, req.body);
  const b = req.body as CaseWireBody;
  validateBody(b, r.caseId);
  applyBody(r, b);
  r.version += 1;
  r.updatedAt = new Date().toISOString();
  r.history.push({ id: `ev-${r.caseId}-${r.history.length + 1}`, at: r.updatedAt, type: 'UPDATED', actorName: FIXTURE_ME.name, note: null });
  return view(r, req);
}

function submit(req: FixtureRequest): CaseView {
  need(req, 'org.onboard_child');
  const r = find(req.params.caseId);
  if (r.status !== 'DRAFT' && r.status !== 'REQUEST_INFO') throw problem(409, 'KYC_STATE', 'Only a draft or returned case can be submitted.');
  checkVersion(r, req.body);
  const now = new Date().toISOString();
  openReview(r, now);
  r.version += 1;
  r.updatedAt = now;
  r.history.push({ id: `ev-${r.caseId}-${r.history.length + 1}`, at: now, type: 'SUBMITTED', actorName: FIXTURE_ME.name, note: `Approval revision ${r.approval?.rev ?? 1} opened` });
  return view(r, req);
}

function decide(req: FixtureRequest): CaseView {
  const b = req.body as { decision: Decision; reason: string | null };
  need(req, b.decision === 'REJECT' ? 'kyc.reject' : 'kyc.approve');
  const r = find(req.params.caseId);
  if (r.status !== 'PENDING_REVIEW') throw problem(409, 'KYC_STATE', 'This case is no longer awaiting a decision.');
  checkVersion(r, req.body);
  if (r.makerId === FIXTURE_ME.id) {
    throw problem(403, 'MAKER_CANNOT_APPROVE', 'You submitted this application — another reviewer must decide.');
  }
  if (!FIXTURE_ME.isChecker) {
    throw problem(403, 'CHECKER_NOT_ENABLED', 'Only designated checkers may decide KYC cases.');
  }
  if (b.decision !== 'APPROVE' && !(b.reason ?? '').trim()) throw problem(400, 'KYC_REASON_REQUIRED', `reason is required for ${b.decision}`);
  const now = new Date().toISOString();
  r.status = b.decision === 'APPROVE' ? 'APPROVED' : b.decision === 'REJECT' ? 'REJECTED' : 'REQUEST_INFO';
  r.decision = { decision: b.decision, reason: b.reason, checkerName: FIXTURE_ME.name, decidedAt: now };
  if (b.decision === 'APPROVE') {
    const years = r.riskRating === 'HIGH' ? 1 : r.riskRating === 'MEDIUM' ? 2 : 3;
    r.nextReviewDate = iso(Date.now() + years * 365 * day);
    r.bankAccounts = r.bankAccounts.map((a) => ({ ...a, status: 'VERIFIED' }));
  }
  r.version += 1;
  r.updatedAt = now;
  r.history.push({
    id: `ev-${r.caseId}-${r.history.length + 1}`, at: now, actorName: FIXTURE_ME.name, note: b.reason,
    type: b.decision === 'APPROVE' ? 'APPROVED' : b.decision === 'REJECT' ? 'REJECTED' : 'REQUEST_INFO',
  });
  return view(r, req);
}

/** the plugin's shape: {caseId, events: [{id, eventType, payload (JSON text), createdAt}]} */
function history(req: FixtureRequest) {
  need(req, 'kyc.review');
  const r = find(req.params.caseId);
  return {
    caseId: r.caseId,
    events: r.history.map((e) => ({
      id: e.id, eventType: e.type, createdAt: e.at,
      payload: JSON.stringify({ actorName: e.actorName, ...(e.note ? { note: e.note } : {}) }),
    })),
  };
}

const emptyPage = { items: [], page: 0, size: 25, hasNext: false, nextAfter: null, sort: 'submittedAt,desc', q: null };

export const KYC_MANAGE_FIXTURES: FixtureSet = {
  'kyc.cases.page': { default: list, empty: emptyPage },
  'kyc.case.view': { default: (req: FixtureRequest) => (need(req, 'kyc.review'), view(find(req.params.caseId), req)) },
  'kyc.case.history': { default: history },
  'kyc.master.list': { default: { items: MASTER }, empty: { items: MASTER } },
  'kyc.case.create': { default: create },
  'kyc.case.update': { default: update },
  'kyc.case.submit': { default: submit },
  'kyc.case.decide': { default: decide },
};
