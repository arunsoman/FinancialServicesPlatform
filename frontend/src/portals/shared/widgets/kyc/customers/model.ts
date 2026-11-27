/**
 * kyc.customers model — customer KYC, owned by the KYC plugin (/api/plugins/kyc).
 *
 * Lifecycle (ui copy and gating follow it exactly):
 *   1. maker registers the customer (no password) → plugin case + core person PENDING;
 *      the IdP sends the activation to the PRIMARY contact (email link / SMS code)
 *   2. the customer opens it and sets their own password at the IdP  → contactVerified
 *   3. a checker (≠ maker) activates the account (approve) → the customer can sign in
 * The server decides every step; the widget only shows affordances.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import type { WidgetDataPortLike } from '../../core/manifest';
import { contactRefine, toE164 } from '../../kit/contact';
import type { PrimaryContact } from '../../kit/contact';
import { kycKeys, retryRead } from '../manage/model';
import type { CaseStatus, CaseView, Page } from '../manage/model';

export const PAGE_SIZES = [10, 25, 50] as const;
export const SORT_KEYS = ['submittedAt', 'legalName', 'status'] as const;
export type SortKey = (typeof SORT_KEYS)[number];

export interface CustomerQuery {
  q: string;
  status: '' | 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED';
  sort: SortKey;
  dir: 'asc' | 'desc';
  page: number;
  size: number;
}
export const DEFAULT_QUERY: CustomerQuery = { q: '', status: '', sort: 'submittedAt', dir: 'desc', page: 0, size: 25 };

export function parseQuery(p: URLSearchParams): CustomerQuery {
  const [sk, sd] = (p.get('csort') ?? '').split(',');
  const page = Number(p.get('cpage'));
  const size = Number(p.get('csize'));
  const st = p.get('cstatus') ?? '';
  const knownSort = (SORT_KEYS as readonly string[]).includes(sk ?? '');
  return {
    q: p.get('cq') ?? '',
    status: st === 'PENDING_REVIEW' || st === 'APPROVED' || st === 'REJECTED' ? st : '',
    // an unknown sort key resets the whole sort (key + direction), never half of it
    sort: knownSort ? (sk as SortKey) : DEFAULT_QUERY.sort,
    dir: knownSort && (sd === 'asc' || sd === 'desc') ? sd : DEFAULT_QUERY.dir,
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: (PAGE_SIZES as readonly number[]).includes(size) ? size : DEFAULT_QUERY.size,
  };
}

/** → the allow-listed params of kyc.customers.page; kind is pinned to CUSTOMER. */
export function queryParams(q: CustomerQuery): Record<string, string> {
  const out: Record<string, string> = { kind: 'CUSTOMER', page: String(q.page), size: String(q.size), sort: `${q.sort},${q.dir}` };
  if (q.q.trim().length >= 2) out.q = q.q.trim();
  if (q.status) out.status = q.status;
  return out;
}

export interface CustomerRow {
  caseId: string;
  caseNo: string;
  legalName: string;
  status: CaseStatus;
  submittedAt: string | null;
  makerName?: string | null;
  primaryContact?: PrimaryContact | null;
  contactVerified?: boolean | null;
  version: number;
}

export interface CustomerDetail {
  personId: string | null;
  firstName: string;
  lastName: string;
  gender: string;
  dateOfBirth: string;
  email: string | null;
  phone: string | null;
  primaryContact: PrimaryContact;
  contactVerified: boolean;
  contactVerifiedAt: string | null;
  activationSentAt: string | null;
}

export type CustomerCaseView = CaseView & { caseKind?: string; customer?: CustomerDetail | null };

/** Where a customer stands — one label per state, used by the table and the drawer. */
export type Stage = 'SETTING_UP' | 'AWAITING_CUSTOMER' | 'AWAITING_CHECKER' | 'ACTIVE' | 'REJECTED';
export function stageOf(r: { status: CaseStatus; contactVerified?: boolean | null }): Stage {
  if (r.status === 'APPROVED') return 'ACTIVE';
  if (r.status === 'REJECTED') return 'REJECTED';
  if (r.status === 'PENDING_REVIEW') return r.contactVerified ? 'AWAITING_CHECKER' : 'AWAITING_CUSTOMER';
  return 'SETTING_UP';
}
export const STAGE_LABEL: Record<Stage, string> = {
  SETTING_UP: 'Setting up',
  AWAITING_CUSTOMER: 'Awaiting customer activation',
  AWAITING_CHECKER: 'Ready for activation',
  ACTIVE: 'Active',
  REJECTED: 'Rejected',
};

export const customerKeys = {
  list: (q: CustomerQuery) => ['kyc', 'cases', 'list', 'customers', q] as const,
};

export function useCustomerPage(port: WidgetDataPortLike, q: CustomerQuery) {
  return useQuery({
    queryKey: customerKeys.list(q),
    queryFn: () => port.request<Page<CustomerRow>>('kyc.customers.page', queryParams(q)),
    placeholderData: (prev) => prev,
    retry: retryRead,
  });
}

export function useCustomerCase(port: WidgetDataPortLike, caseId: string | null) {
  return useQuery({
    queryKey: kycKeys.case(caseId ?? '-'),
    queryFn: () => port.request<CustomerCaseView>('kyc.customers.view', { caseId: caseId ?? '' }),
    enabled: !!caseId,
    retry: retryRead,
  });
}

/* ------------------------------------------------------------------- form */
/** A Namibian address — the same shape the organization application captures (region = REGION master). */
export interface AddressValues {
  line: string;
  city: string;
  region: string;
  postalCode: string;
}
export const emptyAddress = (): AddressValues => ({ line: '', city: '', region: '', postalCode: '' });

export interface CustomerFormValues {
  firstName: string;
  lastName: string;
  gender: string;
  dateOfBirth: string;
  phone: string;
  email: string;
  primaryContact: PrimaryContact;
  /** where the customer lives (required) — the case's registered_* address */
  residentialAddress: AddressValues;
  /** like the org's "operating address same as registered" */
  postalSameAsResidential: boolean;
  postalAddress: AddressValues;
}

export const emptyCustomer = (): CustomerFormValues => ({
  firstName: '', lastName: '', gender: '', dateOfBirth: '', phone: '', email: '', primaryContact: 'EMAIL',
  residentialAddress: emptyAddress(), postalSameAsResidential: true, postalAddress: emptyAddress(),
});

const addressSchema = (word: string) => z.object({
  line: z.string().trim().min(4, `Enter the ${word} street address`).max(200),
  city: z.string().trim().min(2, 'Enter the town or city').max(100),
  region: z.string().min(1, 'Choose a region'),
  postalCode: z.string().trim().max(10, 'Postal code is too long'),
});
const residentialSchema = addressSchema('residential');
const postalSchema = addressSchema('postal');

export const customerSchema = z
  .object({
    firstName: z.string().trim().min(1, 'Enter the first name').max(100),
    lastName: z.string().trim().min(1, 'Enter the last name').max(100),
    gender: z.string().min(1, 'Select a gender'),
    dateOfBirth: z
      .string()
      .regex(/^\d{4}-\d{2}-\d{2}$/, 'Enter the date of birth')
      .refine((v) => new Date(v) < new Date(), 'Date of birth must be in the past'),
    phone: z.string(),
    email: z.string(),
    primaryContact: z.enum(['EMAIL', 'PHONE']),
    residentialAddress: residentialSchema,
    postalSameAsResidential: z.boolean(),
    postalAddress: z.object({ line: z.string(), city: z.string(), region: z.string(), postalCode: z.string() }),
  })
  .superRefine((v, ctx) => {
    contactRefine(v, ctx);
    if (v.postalSameAsResidential) return;
    const r = postalSchema.safeParse(v.postalAddress);
    if (!r.success) for (const i of r.error.issues) ctx.addIssue({ code: 'custom', path: ['postalAddress', ...i.path], message: i.message });
  });

export type FieldErrors = Record<string, string>;
export function validateCustomer(v: CustomerFormValues): FieldErrors {
  const r = customerSchema.safeParse(v);
  if (r.success) return {};
  const out: FieldErrors = {};
  for (const i of r.error.issues) {
    const k = i.path.join('.');
    if (!out[k]) out[k] = i.message;
  }
  return out;
}

export function customerToWire(v: CustomerFormValues) {
  const phone = v.phone.trim();
  return {
    firstName: v.firstName.trim(),
    lastName: v.lastName.trim(),
    gender: v.gender,
    dateOfBirth: v.dateOfBirth,
    phone: phone ? toE164(phone) ?? phone : null,
    email: v.email.trim().toLowerCase() || null,
    primaryContact: v.primaryContact,
    residentialAddress: addressToWire(v.residentialAddress),
    postalSameAsResidential: v.postalSameAsResidential,
    postalAddress: v.postalSameAsResidential ? null : addressToWire(v.postalAddress),
  };
}

function addressToWire(a: AddressValues) {
  return { line: a.line.trim(), city: a.city.trim(), region: a.region, postalCode: a.postalCode.trim() || null };
}

/** Server problem code → the field it belongs to. */
export const FIELD_FOR_CODE: Record<string, keyof CustomerFormValues> = {
  CUSTOMER_EMAIL_TAKEN: 'email',
  CUSTOMER_PHONE_TAKEN: 'phone',
  EMAIL_INVALID: 'email',
  PHONE_INVALID: 'phone',
};

/* ----------------------------------------------------------------- writes */
export type CustomerCommand =
  | { kind: 'create'; values: CustomerFormValues }
  | { kind: 'resend'; view: CustomerCaseView }
  | { kind: 'decide'; view: CustomerCaseView; decision: 'APPROVE' | 'REJECT'; reason: string };

/** Never optimistic, never auto-retried (ui-std §7); the port adds the Idempotency-Key. */
export function useCustomerCommand(port: WidgetDataPortLike) {
  const qc = useQueryClient();
  return useMutation({
    retry: 0,
    mutationFn: async (cmd: CustomerCommand): Promise<CustomerCaseView | { caseId: string }> => {
      switch (cmd.kind) {
        case 'create':
          return port.command<CustomerCaseView>('kyc.customers.create', customerToWire(cmd.values));
        case 'resend':
          return port.command<{ caseId: string }>('kyc.customers.resend', {}, { caseId: cmd.view.caseId });
        case 'decide':
          return port.command<CustomerCaseView>(
            'kyc.customers.decide',
            { decision: cmd.decision, reason: cmd.reason.trim() || null, expectedVersion: cmd.view.version },
            { caseId: cmd.view.caseId },
          );
      }
    },
    onSettled: (saved, _e, cmd) => {
      void qc.invalidateQueries({ queryKey: kycKeys.lists });
      const id = saved?.caseId ?? (cmd.kind === 'create' ? null : cmd.view.caseId);
      if (id) void qc.invalidateQueries({ queryKey: kycKeys.case(id) });
    },
  });
}
