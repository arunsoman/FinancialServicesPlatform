/**
 * kyc.manage — wire types (KYC plugin API, plans/plugin-platform-plan.md §12.4), list-state
 * parsing, form validation and data hooks. Server state rides TanStack Query on top of the
 * widget port, so the ONE network path (port → BFF → gateway → plugin) is preserved and
 * paging / search / sorting are always the SERVER's (the widget never filters a page).
 */
import { contactSchema, contactToWire, emptyContact } from '../../kit/contact';
import type { ContactValues } from '../../kit/contact';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import * as z from 'zod';
import { ApiError } from '../../../web-core/api';
import type { WidgetDataPortLike } from '../../core/manifest';

/* ------------------------------------------------------------ closed sets */
/** The plugin's case states (backend plugins/kyc KycCase.STATUS_*). */
export const CASE_STATUSES = [
  'DRAFT', 'TENANT_PENDING', 'SUBMITTED', 'PENDING_REVIEW', 'REQUEST_INFO', 'APPROVED', 'REJECTED', 'STALLED',
] as const;
export type CaseStatus = (typeof CASE_STATUSES)[number];

export const RISKS = ['LOW', 'MEDIUM', 'HIGH'] as const;
export type Risk = (typeof RISKS)[number];

export const SUB_TYPES = ['MANUFACTURER', 'DISTRIBUTOR', 'DEALER', 'WHOLESALER', 'MERCHANT'] as const;
export type SubType = (typeof SUB_TYPES)[number];

/** Public sort names (the plugin maps them to columns; unknown → 400). */
export const SORT_KEYS = ['submittedAt', 'legalName', 'status', 'riskRating', 'nextReviewDate', 'updatedAt'] as const;
export type SortKey = (typeof SORT_KEYS)[number];
export type SortDir = 'asc' | 'desc';

export const PAGE_SIZES = [10, 25, 50, 100] as const;

/* ------------------------------------------------------------------- wire */
/** Totals-free page (AGENTS §4A): the pager knows only "is there a next page". */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  hasNext: boolean;
  nextAfter: number | null;
  sort: string;
  q: string | null;
}

export interface CaseSummary {
  caseId: string;
  caseNo: string;
  subjectTenantId: string | null;
  legalName: string;
  dbaName: string | null;
  /** null for a CUSTOMER case (a person, not an organization) */
  subType: SubType | null;
  /** null for a CUSTOMER case */
  registrationNumber: string | null;
  status: CaseStatus;
  riskRating: Risk | null;
  submittedAt: string | null;
  nextReviewDate: string | null;
  makerName?: string | null;
  updatedAt: string;
  version: number;
  /** plan field, not yet on the plugin's summary — status STALLED carries it there */
  stalled?: boolean;
  /** V2: ORGANIZATION | CUSTOMER; customers: primary contact + whether they activated */
  caseKind?: 'ORGANIZATION' | 'CUSTOMER';
  primaryContact?: 'EMAIL' | 'PHONE' | null;
  contactVerified?: boolean | null;
}

export interface UboView {
  id: string;
  fullName: string;
  dob?: string | null;
  nationality?: string | null;
  idDocType: string;
  /** masked "•••• 1234" unless the caller holds kyc.pii.read */
  idNumber: string;
  ownershipPct: string;
  isPep: boolean;
}

export interface BankAccountView {
  id: string;
  holderName: string;
  bankName: string;
  branchCode: string;
  /** masked "•••• 1234" unless the caller holds kyc.pii.read */
  accountNumber: string;
  accountType: string;
  status: string;
}

export type CheckResult = 'PASS' | 'MANUAL_REVIEW' | 'FAIL';
export interface CheckView {
  checkId: string;
  checkType: string;
  result: CheckResult;
  details: string;
  simulated: boolean;
  checkedAt: string;
}

export interface ApprovalRef {
  approvalId: string;
  rev: number;
  payloadHash: string;
  makerName: string;
  submittedAt: string;
}

export interface DecisionRef {
  decision: 'APPROVE' | 'REJECT' | 'REQUEST_INFO';
  reason: string | null;
  checkerName: string;
  decidedAt: string;
}

/** Plan codes; the plugin currently sends a human sentence instead — both are rendered. */
export type BlockedReason = 'MAKER' | 'STATE' | 'PERMISSION' | (string & {});

export interface CaseView extends CaseSummary {
  entityType: string;
  incorporationDate: string;
  taxId: string;
  mccCode: string;
  websiteUrl: string | null;
  /** money: minor units as a string, NAD */
  expectedMonthlyVolume: string;
  avgTicketSize: string;
  currency?: string;
  registeredAddress: string;
  registeredCity: string;
  registeredRegion: string;
  registeredPostal: string | null;
  registeredCountry: string;
  operationalAddressSame: boolean;
  operationalAddress: string | null;
  operationalCity: string | null;
  operationalRegion: string | null;
  operationalPostal: string | null;
  ubos: UboView[];
  bankAccounts: BankAccountView[];
  checks: CheckView[];
  approval?: ApprovalRef | null;
  decision?: DecisionRef | null;
  /** computed by the plugin for THIS caller; core enforces it again */
  canDecide: boolean;
  blockedReason: BlockedReason | null;
  /** plan fields the plugin does not send yet — derived in the UI when absent */
  canEdit?: boolean;
  piiMasked?: boolean;
}

export const isStalled = (s: Pick<CaseSummary, 'status' | 'stalled'>) => s.status === 'STALLED' || !!s.stalled;
export const isEditableStatus = (s: CaseStatus) => s === 'DRAFT' || s === 'REQUEST_INFO';
/** blocked because the viewer is the maker (plan code, the plugin's sentence, or core's 403) */
export const isMakerBlock = (r: string | null | undefined) => r === 'MAKER' || /submitted this|four-eyes/i.test(r ?? '');

/** GET /cases/{id}/history — the plugin's shape: {caseId, events[]} (KycDtos.CaseEvent). */
export interface CaseEvent {
  id: string;
  eventType: string;
  /** JSON text; `note` / `reason` / `actorName` are read when present */
  payload: string | null;
  createdAt: string;
}
export interface CaseHistoryResponse {
  caseId: string;
  events: CaseEvent[];
}

export interface MasterValue {
  category: 'entityType' | 'idDocType' | 'accountType' | 'bank' | 'region' | 'mcc' | 'nationality' | 'gender';
  code: string;
  label: string;
}

/* ------------------------------------------------------------- list state */
export interface ListQuery {
  q: string;
  status: CaseStatus[];
  risk: Risk[];
  sort: SortKey;
  dir: SortDir;
  page: number;
  size: number;
}

export const DEFAULT_QUERY: ListQuery = { q: '', status: [], risk: [], sort: 'submittedAt', dir: 'desc', page: 0, size: 25 };

const inSet = <T extends string>(set: readonly T[]) => (v: string): v is T => (set as readonly string[]).includes(v);

/** URL → list query; unknown values are dropped (never forwarded to the server). */
export function parseListQuery(p: URLSearchParams): ListQuery {
  const [sk, sd] = (p.get('ksort') ?? '').split(',');
  const size = Number(p.get('ksize'));
  const page = Number(p.get('kpage'));
  return {
    q: p.get('kq') ?? '',
    status: (p.get('kstatus') ?? '').split(',').filter(inSet(CASE_STATUSES)),
    risk: (p.get('krisk') ?? '').split(',').filter(inSet(RISKS)),
    sort: inSet(SORT_KEYS)(sk ?? '') ? (sk as SortKey) : DEFAULT_QUERY.sort,
    dir: sd === 'asc' || sd === 'desc' ? sd : sk ? 'asc' : DEFAULT_QUERY.dir,
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: (PAGE_SIZES as readonly number[]).includes(size) ? size : DEFAULT_QUERY.size,
  };
}

/** List query → the source's allow-listed params (sources.ts kyc.cases.page). */
export function listParams(q: ListQuery): Record<string, string> {
  const out: Record<string, string> = { page: String(q.page), size: String(q.size), sort: `${q.sort},${q.dir}` };
  if (q.q.trim().length >= 2) out.q = q.q.trim();
  if (q.status.length) out.status = q.status.join(',');
  if (q.risk.length) out.risk = q.risk.join(',');
  out.kind = 'ORGANIZATION'; // customers live in their own widget (kyc.customers)
  return out;
}

/* --------------------------------------------------------------- problems */
export interface Problem {
  status: number;
  code: string;
  message: string;
}

export function toProblem(err: unknown): Problem {
  if (err instanceof ApiError) {
    // web-core maps every 403 to code AUTH_FORBIDDEN and carries the server's code in `title`
    // (e.g. MAKER_CANNOT_APPROVE) — the specific code is what the UI must render (ui-std §5.2).
    const code = err.status === 403 ? (err.title || err.code || 'AUTH_FORBIDDEN') : (err.code ?? `HTTP_${err.status}`);
    return { status: err.status, code, message: err.detail };
  }
  return { status: 0, code: 'NETWORK', message: err instanceof Error ? err.message : String(err) };
}

export const isNotReady = (p: Problem | null) => p?.status === 503 && p.code === 'PLUGIN_NOT_READY';

/** GETs retry once on transient failures only — never on 4xx or "plugin not ready". */
export function retryRead(n: number, err: unknown): boolean {
  const s = err instanceof ApiError ? err.status : 0;
  return n < 1 && (s === 0 || (s >= 500 && s !== 503));
}

/* ------------------------------------------------------------- query keys */
export const kycKeys = {
  all: ['kyc'] as const,
  lists: ['kyc', 'cases', 'list'] as const,
  list: (q: ListQuery) => ['kyc', 'cases', 'list', q] as const,
  case: (id: string) => ['kyc', 'cases', 'one', id] as const,
  history: (id: string) => ['kyc', 'cases', 'history', id] as const,
  master: ['kyc', 'master'] as const,
};

/* ------------------------------------------------------------------ reads */
export function useCasePage(port: WidgetDataPortLike, q: ListQuery) {
  return useQuery({
    queryKey: kycKeys.list(q),
    queryFn: () => port.request<Page<CaseSummary>>('kyc.cases.page', listParams(q)),
    placeholderData: (prev) => prev,
    retry: retryRead,
  });
}

export function useCase(port: WidgetDataPortLike, caseId: string | null) {
  return useQuery({
    queryKey: kycKeys.case(caseId ?? '-'),
    queryFn: () => port.request<CaseView>('kyc.case.view', { caseId: caseId ?? '' }),
    enabled: !!caseId,
    retry: retryRead,
  });
}

export function useCaseHistory(port: WidgetDataPortLike, caseId: string, enabled: boolean) {
  return useQuery({
    queryKey: kycKeys.history(caseId),
    queryFn: () => port.request<CaseHistoryResponse>('kyc.case.history', { caseId, page: '0', size: '50' }),
    enabled,
    retry: retryRead,
  });
}

/** The plugin's reference-data categories (GET /v1/plugins/kyc/master-values) → the form's fields. */
const WIRE_CATEGORY: Record<string, MasterValue['category']> = {
  ENTITY_TYPE: 'entityType', DOC_TYPE: 'idDocType', ACCOUNT_TYPE: 'accountType', BANK: 'bank',
  REGION: 'region', MCC: 'mcc', COUNTRY: 'nationality', GENDER: 'gender',
};

export interface WireMasterValue { category: string; code: string; label: string }

/** Unknown categories are dropped (fail closed: never offer a value the plugin did not publish). */
export function fromWire(items: readonly WireMasterValue[] | undefined): MasterValue[] {
  return (items ?? []).flatMap((v) => {
    const category = WIRE_CATEGORY[v.category];
    return category ? [{ category, code: v.code, label: v.label }] : [];
  });
}

export function useMasterValues(port: WidgetDataPortLike, enabled: boolean) {
  return useQuery({
    queryKey: kycKeys.master,
    queryFn: async () => fromWire((await port.request<{ items?: WireMasterValue[] }>('kyc.master.list', {})).items),
    enabled,
    staleTime: 30 * 60_000,
    retry: retryRead,
  });
}

export function optionsOf(values: MasterValue[] | undefined, category: MasterValue['category']) {
  return (values ?? []).filter((v) => v.category === category).map((v) => ({ value: v.code, label: v.label }));
}

/* ----------------------------------------------------------------- writes */
export type Decision = 'APPROVE' | 'REJECT' | 'REQUEST_INFO';

export type CaseCommand =
  | { kind: 'create'; values: CaseFormValues }
  | { kind: 'update'; view: CaseView; values: CaseFormValues }
  | { kind: 'submit'; view: CaseView }
  | { kind: 'decide'; view: CaseView; decision: Decision; reason: string };

/**
 * Every KYC write: never optimistic, never auto-retried (ui-std §7); the port adds the
 * Idempotency-Key. On success the confirmed view is written through, then the list,
 * case and history are invalidated explicitly.
 */
export function useCaseCommand(port: WidgetDataPortLike) {
  const qc = useQueryClient();
  return useMutation({
    retry: 0,
    mutationFn: (cmd: CaseCommand): Promise<CaseView> => {
      switch (cmd.kind) {
        case 'create':
          return port.command<CaseView>('kyc.case.create', toWire(cmd.values));
        case 'update':
          return port.command<CaseView>(
            'kyc.case.update',
            { ...toWire(cmd.values), expectedVersion: cmd.view.version },
            { caseId: cmd.view.caseId },
          );
        case 'submit':
          return port.command<CaseView>('kyc.case.submit', { expectedVersion: cmd.view.version }, { caseId: cmd.view.caseId });
        case 'decide':
          return port.command<CaseView>(
            'kyc.case.decide',
            { decision: cmd.decision, reason: cmd.reason.trim() || null, expectedVersion: cmd.view.version },
            { caseId: cmd.view.caseId },
          );
      }
    },
    onSuccess: (saved) => {
      if (saved && typeof saved.caseId === 'string') qc.setQueryData(kycKeys.case(saved.caseId), saved);
    },
    onSettled: (saved, _err, cmd) => {
      void qc.invalidateQueries({ queryKey: kycKeys.lists });
      const id = saved?.caseId ?? (cmd.kind === 'create' ? null : cmd.view.caseId);
      if (id) {
        void qc.invalidateQueries({ queryKey: kycKeys.case(id) });
        void qc.invalidateQueries({ queryKey: kycKeys.history(id) });
      }
    },
  });
}

/* ------------------------------------------------------------- validation */
/** NamibiaRules mirrors (AGENTS §3.10) so most mistakes are caught on blur. */
export const MASK = '••••';
const isMasked = (v: string) => v.startsWith(MASK);
const normReg = (v: string) => v.toUpperCase().replace(/[\s/-]/g, '');
const money = z
  .string()
  .trim()
  .regex(/^\d{1,13}(\.\d{1,2})?$/, 'Enter an amount in NAD, e.g. 250000 or 1250.50');
const pastDate = (msg: string) =>
  z.string().regex(/^\d{4}-\d{2}-\d{2}$/, msg).refine((v) => new Date(v) <= new Date(), 'Can’t be in the future');

export const uboSchema = z.object({
  id: z.string(),
  fullName: z.string().trim().min(2, 'Enter the owner’s full name').max(120),
  dob: pastDate('Enter a date of birth'),
  nationality: z.string().min(2, 'Choose a nationality'),
  idDocType: z.string().min(1, 'Choose a document type'),
  idNumber: z
    .string()
    .trim()
    .refine((v) => isMasked(v) || /^[A-Z0-9]{6,20}$/i.test(v), '6–20 letters or digits'),
  ownershipPct: z
    .string()
    .trim()
    .refine((v) => /^\d{1,3}(\.\d{1,2})?$/.test(v) && Number(v) >= 25 && Number(v) <= 100, 'Beneficial owners hold 25% – 100%'),
  isPep: z.boolean(),
});

export const bankSchema = z.object({
  id: z.string(),
  holderName: z.string().trim().min(2, 'Enter the account holder'),
  bankName: z.string().min(1, 'Choose a bank'),
  branchCode: z.string().trim().regex(/^\d{5,6}$/, 'Branch codes are 5 or 6 digits'),
  accountNumber: z
    .string()
    .trim()
    .refine((v) => isMasked(v) || /^\d{6,16}$/.test(v), '6–16 digits'),
  accountType: z.string().min(1, 'Choose an account type'),
});

export const STEPS = [
  { id: 'company', label: 'Company' },
  { id: 'business', label: 'Business' },
  { id: 'addresses', label: 'Addresses' },
  { id: 'owners', label: 'UBO' },
  { id: 'banking', label: 'Bank accounts' },
  { id: 'access', label: 'Sign-in' },
] as const;
export type StepId = (typeof STEPS)[number]['id'];

const stepSchemas = {
  company: z.object({
    subType: z.enum(SUB_TYPES, { errorMap: () => ({ message: 'Choose the organization type' }) }),
    legalName: z.string().trim().min(2, 'Enter the registered name').max(160),
    dbaName: z.string().trim().max(160),
    entityType: z.string().min(1, 'Choose the entity type'),
    registrationNumber: z
      .string()
      .trim()
      .refine((v) => /^([A-Z]{2})?\d{3,10}$/.test(normReg(v)), 'BIPA number, e.g. CC/2019/04512 or 2019/0451'),
    incorporationDate: pastDate('Enter the incorporation date'),
    taxId: z.string().trim().regex(/^\d{8}$/, 'TIN is 8 digits'),
  }),
  business: z.object({
    mccCode: z.string().regex(/^\d{4}$/, 'Choose a merchant category'),
    websiteUrl: z
      .string()
      .trim()
      .refine((v) => v === '' || /^https:\/\/[^\s.]+\.[^\s]{2,}$/.test(v), 'Use a full https:// address'),
    expectedMonthlyVolume: money,
    avgTicketSize: money,
  }),
  addresses: z
    .object({
      registeredAddress: z.string().trim().min(4, 'Enter the street address'),
      registeredCity: z.string().trim().min(2, 'Enter the town or city'),
      registeredRegion: z.string().min(1, 'Choose a region'),
      registeredPostal: z.string().trim().max(10),
      operationalAddressSame: z.boolean(),
      operationalAddress: z.string().trim(),
      operationalCity: z.string().trim(),
      operationalRegion: z.string(),
      operationalPostal: z.string().trim().max(10),
    })
    .superRefine((v, ctx) => {
      if (v.operationalAddressSame) return;
      if (v.operationalAddress.length < 4) ctx.addIssue({ code: 'custom', path: ['operationalAddress'], message: 'Enter the operating address' });
      if (v.operationalCity.length < 2) ctx.addIssue({ code: 'custom', path: ['operationalCity'], message: 'Enter the town or city' });
      if (!v.operationalRegion) ctx.addIssue({ code: 'custom', path: ['operationalRegion'], message: 'Choose a region' });
    }),
  owners: z
    .object({ ubos: z.array(uboSchema).min(1, 'Add at least one beneficial owner (≥ 25%)') })
    .superRefine((v, ctx) => {
      const total = v.ubos.reduce((s, u) => s + (Number(u.ownershipPct) || 0), 0);
      if (total > 100) ctx.addIssue({ code: 'custom', path: ['ubos'], message: `Ownership adds up to ${total}% — it can’t exceed 100%` });
    }),
  banking: z.object({ bankAccounts: z.array(bankSchema).min(1, 'Add the settlement bank account') }),
  // V2: the new org's administrator + checker activate by link/code on their primary contact
  access: z.object({ admin: contactSchema, checker: contactSchema }),
} satisfies Record<StepId, z.ZodTypeAny>;

export interface CaseFormValues {
  subType: SubType | '';
  legalName: string;
  dbaName: string;
  entityType: string;
  registrationNumber: string;
  incorporationDate: string;
  taxId: string;
  mccCode: string;
  websiteUrl: string;
  expectedMonthlyVolume: string;
  avgTicketSize: string;
  registeredAddress: string;
  registeredCity: string;
  registeredRegion: string;
  registeredPostal: string;
  operationalAddressSame: boolean;
  operationalAddress: string;
  operationalCity: string;
  operationalRegion: string;
  operationalPostal: string;
  ubos: z.infer<typeof uboSchema>[];
  bankAccounts: z.infer<typeof bankSchema>[];
  /** create only: who runs the new organization (each gets an activation, no shared password) */
  admin: ContactValues;
  checker: ContactValues;
}
export type UboValues = CaseFormValues['ubos'][number];
export type BankValues = CaseFormValues['bankAccounts'][number];

/** field path ("ubos.0.idNumber") → message */
export type FieldErrors = Record<string, string>;

export function validateStep(step: StepId, v: CaseFormValues): FieldErrors {
  const r = stepSchemas[step].safeParse(v);
  if (r.success) return {};
  const out: FieldErrors = {};
  for (const i of r.error.issues) {
    const k = i.path.join('.');
    if (!out[k]) out[k] = i.message;
  }
  return out;
}

/** Which step owns a field path (server problems are routed back to it). */
export function stepOf(path: string): StepId {
  const head = path.split('.')[0];
  if (head === 'ubos') return 'owners';
  if (head === 'bankAccounts') return 'banking';
  if (head === 'admin' || head === 'checker') return 'access';
  if (head.startsWith('registered') || head.startsWith('operational')) return 'addresses';
  if (['mccCode', 'websiteUrl', 'expectedMonthlyVolume', 'avgTicketSize'].includes(head)) return 'business';
  return 'company';
}

/** Plugin problem code → the field it belongs to (otherwise a form-level message). */
export const FIELD_FOR_CODE: Record<string, string> = {
  CONTACT_REQUIRED: 'admin.email',
  REGISTRATION_TAKEN: 'registrationNumber',
  REGISTRATION_INVALID: 'registrationNumber',
  TIN_INVALID: 'taxId',
  BRANCH_CODE_INVALID: 'bankAccounts.0.branchCode',
  UBO_THRESHOLD: 'ubos',
};

let seq = 0;
export const newRowId = () => `new-${Date.now().toString(36)}-${++seq}`;

export const emptyUbo = (): UboValues => ({
  id: newRowId(), fullName: '', dob: '', nationality: 'NA', idDocType: '', idNumber: '', ownershipPct: '', isPep: false,
});
export const emptyBank = (): BankValues => ({
  id: newRowId(), holderName: '', bankName: '', branchCode: '', accountNumber: '', accountType: '',
});

export function emptyForm(): CaseFormValues {
  return {
    subType: '', legalName: '', dbaName: '', entityType: '', registrationNumber: '', incorporationDate: '', taxId: '',
    mccCode: '', websiteUrl: '', expectedMonthlyVolume: '', avgTicketSize: '',
    registeredAddress: '', registeredCity: '', registeredRegion: '', registeredPostal: '',
    operationalAddressSame: true, operationalAddress: '', operationalCity: '', operationalRegion: '', operationalPostal: '',
    ubos: [emptyUbo()], bankAccounts: [emptyBank()],
    admin: emptyContact(), checker: emptyContact(),
  };
}

export function formFromView(v: CaseView): CaseFormValues {
  return {
    subType: v.subType ?? '', legalName: v.legalName, dbaName: v.dbaName ?? '', entityType: v.entityType,
    registrationNumber: v.registrationNumber ?? '', incorporationDate: v.incorporationDate, taxId: v.taxId,
    mccCode: v.mccCode, websiteUrl: v.websiteUrl ?? '',
    expectedMonthlyVolume: minorToMajor(v.expectedMonthlyVolume), avgTicketSize: minorToMajor(v.avgTicketSize),
    registeredAddress: v.registeredAddress, registeredCity: v.registeredCity, registeredRegion: v.registeredRegion,
    registeredPostal: v.registeredPostal ?? '', operationalAddressSame: v.operationalAddressSame,
    operationalAddress: v.operationalAddress ?? '', operationalCity: v.operationalCity ?? '',
    operationalRegion: v.operationalRegion ?? '', operationalPostal: v.operationalPostal ?? '',
    ubos: v.ubos.map((u) => ({
      id: u.id, fullName: u.fullName, dob: u.dob ?? '', nationality: u.nationality ?? 'NA', idDocType: u.idDocType,
      idNumber: u.idNumber, ownershipPct: u.ownershipPct, isPep: u.isPep,
    })),
    bankAccounts: v.bankAccounts.map((b) => ({
      id: b.id, holderName: b.holderName, bankName: b.bankName, branchCode: b.branchCode,
      accountNumber: b.accountNumber, accountType: b.accountType,
    })),
    // edit never changes the org's people (they exist in core already)
    admin: emptyContact(), checker: emptyContact(),
  };
}

/** Form → wire body: amounts as minor-unit strings, masked secrets as null (= keep stored). */
export function toWire(v: CaseFormValues) {
  const keep = (s: string) => (isMasked(s) ? null : s.trim());
  return {
    subType: v.subType,
    legalName: v.legalName.trim(),
    dbaName: v.dbaName.trim() || null,
    entityType: v.entityType,
    registrationNumber: normReg(v.registrationNumber),
    incorporationDate: v.incorporationDate,
    taxId: v.taxId.trim(),
    mccCode: v.mccCode,
    websiteUrl: v.websiteUrl.trim() || null,
    expectedMonthlyVolume: majorToMinor(v.expectedMonthlyVolume),
    avgTicketSize: majorToMinor(v.avgTicketSize),
    currency: 'NAD',
    registeredAddress: v.registeredAddress.trim(),
    registeredCity: v.registeredCity.trim(),
    registeredRegion: v.registeredRegion,
    registeredPostal: v.registeredPostal.trim() || null,
    registeredCountry: 'NA',
    operationalAddressSame: v.operationalAddressSame,
    operationalAddress: v.operationalAddressSame ? null : v.operationalAddress.trim(),
    operationalCity: v.operationalAddressSame ? null : v.operationalCity.trim(),
    operationalRegion: v.operationalAddressSame ? null : v.operationalRegion,
    operationalPostal: v.operationalAddressSame ? null : v.operationalPostal.trim() || null,
    ubos: v.ubos.map((u) => ({
      id: u.id.startsWith('new-') ? null : u.id,
      fullName: u.fullName.trim(), dob: u.dob, nationality: u.nationality, idDocType: u.idDocType,
      idNumber: keep(u.idNumber), ownershipPct: u.ownershipPct.trim(), isPep: u.isPep,
    })),
    bankAccounts: v.bankAccounts.map((b) => ({
      id: b.id.startsWith('new-') ? null : b.id,
      holderName: b.holderName.trim(), bankName: b.bankName, branchCode: b.branchCode.trim(),
      accountNumber: keep(b.accountNumber), accountType: b.accountType,
    })),
    admin: contactToWire(v.admin),
    checker: contactToWire(v.checker),
  };
}
export type CaseWireBody = ReturnType<typeof toWire>;

/* ---------------------------------------------------------------- helpers */
/** "1250.5" NAD → "125050" minor units (string — JS-safe, AGENTS §3.2). */
export function majorToMinor(v: string): string {
  const [a, b = ''] = v.trim().split('.');
  return String(BigInt(a || '0') * 100n + BigInt((b + '00').slice(0, 2)));
}

export function minorToMajor(v: string): string {
  const n = BigInt(v || '0');
  const cents = n % 100n;
  return cents === 0n ? String(n / 100n) : `${n / 100n}.${String(cents).padStart(2, '0')}`;
}

/** Display a minor-unit amount (tabular figures are applied by the caller). */
export function formatMinor(v: string, currency: string): string {
  const n = BigInt(v || '0');
  const whole = (n / 100n).toLocaleString('en-NA');
  return `${currency} ${whole}.${String(n % 100n).padStart(2, '0')}`;
}

const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });

export function relativeTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const diff = (new Date(iso).getTime() - Date.now()) / 1000;
  const abs = Math.abs(diff);
  if (abs < 60) return rtf.format(Math.round(diff), 'second');
  if (abs < 3600) return rtf.format(Math.round(diff / 60), 'minute');
  if (abs < 86400) return rtf.format(Math.round(diff / 3600), 'hour');
  if (abs < 86400 * 30) return rtf.format(Math.round(diff / 86400), 'day');
  return new Date(iso).toLocaleDateString();
}

export function formatDate(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }) : '—';
}

export function absoluteTime(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString() : '—';
}

/** Review is overdue / due within 30 days — surfaced on the list. */
export function reviewDue(iso: string | null): 'overdue' | 'soon' | null {
  if (!iso) return null;
  const days = (new Date(iso).getTime() - Date.now()) / 86_400_000;
  return days < 0 ? 'overdue' : days < 30 ? 'soon' : null;
}

/**
 * The only organization type `orgId` may onboard — core's ladder (TenantLadder:
 * Platform → Manufacturer → Distributor → Wholesaler → Merchant). null = it onboards none.
 * Core re-decides on create; this only fixes the form so a wrong type is never filed
 * (the KYC case would otherwise be saved before core refuses the org).
 */
export function useNextLevel(port: WidgetDataPortLike, orgId: string) {
  const q = useQuery({
    queryKey: ['tenant', 'next-level', orgId],
    queryFn: () => port.request<{ legalName?: string; childSubType?: string | null }>('kyc.orgs.self', { tenantId: orgId }),
    enabled: orgId !== '',
  });
  const childSubType = (q.data?.childSubType ?? null) as CaseFormValues['subType'] | null;
  const label = childSubType ? childSubType.charAt(0) + childSubType.slice(1).toLowerCase() : null;
  return { childSubType, label, orgName: q.data?.legalName ?? 'this organization', loaded: q.isSuccess };
}

/* ------------------------------------------------- the org-type ladder */
export const SUB_OPTIONS = SUB_TYPES.map((s) => ({ value: s, label: s.charAt(0) + s.slice(1).toLowerCase() }));

/**
 * New applications offer ONLY the child level of the signed-in org (core TenantLadder) — never
 * a sibling or a level above; with the level unknown nothing is offered. An existing case shows
 * the type it already has (fixed once the organization exists).
 */
export function subTypeOptions(mode: 'create' | 'edit', lockedSubType: string | undefined, current: string) {
  if (mode === 'edit') return SUB_OPTIONS.filter((o) => o.value === current);
  return lockedSubType ? SUB_OPTIONS.filter((o) => o.value === lockedSubType) : [];
}
