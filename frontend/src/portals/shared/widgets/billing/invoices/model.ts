/**
 * billing.invoices — wire types, pure logic (credit terms, due state, exact money) and hooks.
 *
 * Money: minor-unit STRINGS on the wire (ui-std §5.3). The form preview computes line totals with
 * BigInt (never floats); the SERVER computes the invoice's real totals and they are what we show
 * once saved. Writes are never optimistic and never auto-retried (ui-std §7).
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import type { WidgetDataPortLike } from '../../core/manifest';

/* ─────────────────────────────── wire types (the contract) ─────────────────────────────── */

export type InvoiceStatus = 'DRAFT' | 'ISSUED' | 'PARTIALLY_PAID' | 'PAID' | 'VOID_PENDING' | 'VOID';
export type Direction = 'SALES' | 'PURCHASES' | 'ALL';
export type PaymentMethod = 'WALLET' | 'BANK_TRANSFER' | 'CARD';
/** PENDING = sent to the rail · AWAITING_CONFIRMATION = buyer says a bank transfer was made ·
 *  COMMIT_UNKNOWN = the rail did not answer; only SUCCEEDED reduces the balance. */
export type PaymentStatus = 'PENDING' | 'AWAITING_CONFIRMATION' | 'SUCCEEDED' | 'FAILED' | 'COMMIT_UNKNOWN';

export interface PartyRef {
  tenantId: string;
  name: string;
}

/** One row of GET /invoices (a page item). */
export interface InvoiceSummary {
  invoiceId: string;
  /** seller-scoped running number, e.g. INV-2026-0007; null while DRAFT */
  number: string | null;
  seller: PartyRef;
  buyer: PartyRef;
  currency: string;
  /** null while DRAFT (the issue date is set when it is issued) */
  issueDate: string | null;
  dueDate: string | null;
  creditDays: number;
  totalMinor: string;
  paidMinor: string;
  balanceMinor: string;
  status: InvoiceStatus;
  version: number;
}

export interface InvoiceLineView {
  lineNo: number;
  description: string;
  quantity: number;
  unitPriceMinor: string;
  /** VAT rate in basis points (1500 = 15 %) */
  taxRateBps: number;
  netMinor: string;
  taxMinor: string;
  totalMinor: string;
}

export interface PaymentView {
  paymentId: string;
  method: PaymentMethod;
  amountMinor: string;
  status: PaymentStatus;
  /** the reference the buyer quotes (bank transfer) or the rail's reference */
  reference: string | null;
  createdAt: string;
  confirmedAt: string | null;
  /** CARD only: the PSP's hosted payment page — card details are entered there, never here */
  redirectUrl: string | null;
}

/** GET /invoices/{invoiceId} */
export interface InvoiceView extends InvoiceSummary {
  /** the date a DRAFT will be issued with (today unless the seller chose another) */
  plannedIssueDate: string;
  reference: string | null;
  notes: string | null;
  subtotalMinor: string;
  taxMinor: string;
  lines: InvoiceLineView[];
  payments: PaymentView[];
  /** set while a void waits for a second person (four-eyes) */
  voidApprovalId: string | null;
  createdAt: string;
}

export interface InvoicePage {
  items: InvoiceSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

/** GET /invoices/summary — server-computed, never summed client-side */
export interface InvoiceTotals {
  direction: Direction;
  currency: string;
  outstandingMinor: string;
  overdueMinor: string;
  dueSoonMinor: string;
  openCount: number;
  overdueCount: number;
}

/** GET /invoices/counterparties — the organisations this one may invoice */
export interface Counterparty {
  tenantId: string;
  name: string;
  subType: string;
  currency: string;
}

/** GET /invoices/{invoiceId}/payment-options — the server decides what is available */
export interface PaymentOption {
  method: PaymentMethod;
  available: boolean;
  /** why not, in words, when unavailable */
  reason: string | null;
  /** WALLET: the buyer's e-money balance */
  walletBalanceMinor: string | null;
  /** BANK_TRANSFER: who to pay — bank name and the last digits only, never a full account */
  payee: { name: string; bankName: string; accountEnding: string } | null;
  /** BANK_TRANSFER: the reference the buyer must quote */
  reference: string | null;
}

/* ───────────────────────────── credit terms and due state ───────────────────────────── */

export const DEFAULT_CREDIT_DAYS = 30;
export const CREDIT_TERMS: readonly { days: number; label: string }[] = [
  { days: 0, label: 'Due on receipt' },
  { days: 7, label: '7 days' },
  { days: 15, label: '15 days' },
  { days: 30, label: '30 days (standard)' },
  { days: 45, label: '45 days' },
  { days: 60, label: '60 days' },
  { days: 90, label: '90 days' },
];
export const DUE_SOON_DAYS = 7;

const DAY_MS = 86_400_000;
const isoDate = (d: Date) => d.toISOString().slice(0, 10);
const utc = (iso: string) => Date.UTC(Number(iso.slice(0, 4)), Number(iso.slice(5, 7)) - 1, Number(iso.slice(8, 10)));

/** Issue date + credit days, as calendar days (UTC — no DST drift). */
export function dueDateFor(issueDate: string, creditDays: number): string {
  return isoDate(new Date(utc(issueDate) + creditDays * DAY_MS));
}

/** Whole days from `from` to `to` (negative when `to` is earlier). */
export function daysBetween(from: string, to: string): number {
  return Math.round((utc(to) - utc(from)) / DAY_MS);
}

export function todayIso(now: Date = new Date()): string {
  return isoDate(now);
}

export type Tone = 'ok' | 'warn' | 'bad' | 'info' | 'neutral' | 'accent';

export interface DueState {
  label: string;
  tone: Tone;
}

/** What the "Due" column says for an invoice on `today`. */
export function dueState(inv: Pick<InvoiceSummary, 'status' | 'dueDate' | 'creditDays'>, today: string): DueState {
  switch (inv.status) {
    case 'DRAFT':
      return { label: `${termsLabel(inv.creditDays)} once issued`, tone: 'neutral' };
    case 'PAID':
      return { label: 'Paid', tone: 'ok' };
    case 'VOID':
      return { label: 'Void', tone: 'neutral' };
    case 'VOID_PENDING':
      return { label: 'Void awaiting approval', tone: 'warn' };
    default:
      break;
  }
  if (!inv.dueDate) return { label: 'No due date', tone: 'neutral' };
  const d = daysBetween(today, inv.dueDate);
  if (d < 0) return { label: `Overdue by ${-d} day${d === -1 ? '' : 's'}`, tone: 'bad' };
  if (d === 0) return { label: 'Due today', tone: 'warn' };
  if (d <= DUE_SOON_DAYS) return { label: `Due in ${d} day${d === 1 ? '' : 's'}`, tone: 'warn' };
  return { label: `Due in ${d} days`, tone: 'info' };
}

export function termsLabel(days: number): string {
  return days === 0 ? 'Due on receipt' : `Net ${days}`;
}

export const STATUS_LABEL: Record<InvoiceStatus, string> = {
  DRAFT: 'Draft', ISSUED: 'Open', PARTIALLY_PAID: 'Part paid', PAID: 'Paid', VOID_PENDING: 'Void pending', VOID: 'Void',
};
export const STATUS_TONE: Record<InvoiceStatus, Tone> = {
  DRAFT: 'neutral', ISSUED: 'info', PARTIALLY_PAID: 'accent', PAID: 'ok', VOID_PENDING: 'warn', VOID: 'neutral',
};

export const PAYMENT_LABEL: Record<PaymentStatus, string> = {
  PENDING: 'Processing', AWAITING_CONFIRMATION: 'Awaiting seller confirmation', SUCCEEDED: 'Received',
  FAILED: 'Failed', COMMIT_UNKNOWN: 'Outcome unknown',
};
export const PAYMENT_TONE: Record<PaymentStatus, Tone> = {
  PENDING: 'info', AWAITING_CONFIRMATION: 'warn', SUCCEEDED: 'ok', FAILED: 'bad', COMMIT_UNKNOWN: 'warn',
};
export const METHOD_LABEL: Record<PaymentMethod, string> = {
  WALLET: 'E-money wallet', BANK_TRANSFER: 'Bank transfer (EFT)', CARD: 'Card',
};

/** The status filter chips (server-side filter values). OVERDUE is a server-derived view. */
export const STATUS_FILTERS = ['ALL', 'DRAFT', 'OPEN', 'OVERDUE', 'PAID', 'VOID'] as const;
export type StatusFilter = (typeof STATUS_FILTERS)[number];
export const FILTER_LABEL: Record<StatusFilter, string> = {
  ALL: 'All', DRAFT: 'Drafts', OPEN: 'Open', OVERDUE: 'Overdue', PAID: 'Paid', VOID: 'Void',
};

/** Which filters make sense for a tab — a draft is the seller's alone (not the buyer's, not the platform's). */
export function filtersFor(direction: Direction): readonly StatusFilter[] {
  return direction === 'SALES' ? STATUS_FILTERS : STATUS_FILTERS.filter((f) => f !== 'DRAFT');
}

/* ─────────────────────────────────── exact money ─────────────────────────────────── */

/** Major-unit text ("2500.00") → minor-unit string ("250000") without floating point. */
export function toMinor(major: string, exponent = 2): string | null {
  const m = /^(\d{1,15})(?:\.(\d{1,3}))?$/.exec(major.trim().replace(/,/g, ''));
  if (!m) return null;
  const frac = (m[2] ?? '').padEnd(exponent, '0');
  if (frac.length > exponent) return null;
  const minor = `${m[1]}${frac}`.replace(/^0+(?=\d)/, '');
  return minor === '0' ? null : minor;
}

/** Tax = net × bps / 10 000, rounded half-up to the minor unit (BigInt, exact). */
export function lineAmounts(quantity: number, unitPriceMinor: string, taxRateBps: number) {
  const net = BigInt(quantity) * BigInt(unitPriceMinor);
  const tax = (net * BigInt(taxRateBps) * 2n + 10_000n) / 20_000n;
  return { netMinor: net.toString(), taxMinor: tax.toString(), totalMinor: (net + tax).toString() };
}

/** Preview of the totals a draft will have — the server recomputes and its numbers win. */
export function previewTotals(lines: readonly LineValues[]) {
  let sub = 0n;
  let tax = 0n;
  for (const l of lines) {
    const unit = toMinor(l.unitPrice);
    const qty = Number(l.quantity);
    if (!unit || !Number.isInteger(qty) || qty < 1) continue;
    const a = lineAmounts(qty, unit, Number(l.taxRateBps));
    sub += BigInt(a.netMinor);
    tax += BigInt(a.taxMinor);
  }
  return { subtotalMinor: sub.toString(), taxMinor: tax.toString(), totalMinor: (sub + tax).toString() };
}

/** a ≤ b for minor-unit strings */
export function minorLte(a: string, b: string): boolean {
  return BigInt(a) <= BigInt(b);
}

/* ─────────────────────────────────── forms ─────────────────────────────────── */

export const TAX_RATES: readonly { bps: string; label: string }[] = [
  { bps: '1500', label: 'VAT 15%' },
  { bps: '0', label: 'Zero-rated' },
];
export const CURRENCIES = ['NAD', 'ZAR'] as const;

const lineSchema = z.object({
  description: z.string().trim().min(2, 'Describe the item').max(200),
  quantity: z.string().trim().regex(/^[1-9]\d{0,5}$/, 'Whole number from 1'),
  unitPrice: z.string().trim().refine((v) => toMinor(v) !== null, 'Enter a price above zero, e.g. 1250.00'),
  taxRateBps: z.enum(['1500', '0']),
});

export const invoiceSchema = z.object({
  buyerTenantId: z.string().min(1, 'Choose who you are invoicing'),
  currency: z.enum(CURRENCIES),
  issueDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Choose the issue date'),
  creditDays: z.number().int().min(0).max(365),
  reference: z.string().trim().max(64),
  notes: z.string().trim().max(500),
  lines: z.array(lineSchema).min(1, 'Add at least one line'),
});
export type InvoiceValues = z.infer<typeof invoiceSchema>;
export type LineValues = InvoiceValues['lines'][number];

export const blankLine = (): LineValues => ({ description: '', quantity: '1', unitPrice: '', taxRateBps: '1500' });

export function blankInvoice(today: string): InvoiceValues {
  return { buyerTenantId: '', currency: 'NAD', issueDate: today, creditDays: DEFAULT_CREDIT_DAYS, reference: '', notes: '', lines: [blankLine()] };
}

/** The POST /invoices body (what port.command sends). */
export function toCreateBody(v: InvoiceValues) {
  return {
    buyerTenantId: v.buyerTenantId,
    currency: v.currency,
    issueDate: v.issueDate,
    creditDays: v.creditDays,
    reference: v.reference || null,
    notes: v.notes || null,
    lines: v.lines.map((l) => ({
      description: l.description.trim(),
      quantity: Number(l.quantity),
      unitPriceMinor: toMinor(l.unitPrice),
      taxRateBps: Number(l.taxRateBps),
    })),
  };
}

/** Pay form: the amount is major-unit text; at most the balance (part payments allowed). */
export function payAmountError(major: string, balanceMinor: string): string | null {
  const minor = toMinor(major);
  if (!minor) return 'Enter an amount above zero';
  if (!minorLte(minor, balanceMinor)) return 'That is more than the balance due';
  return null;
}

/** "1250.5" style major text for a minor string (prefills the pay amount with the balance). */
export function toMajorText(minor: string, exponent = 2): string {
  const s = minor.padStart(exponent + 1, '0');
  return `${s.slice(0, -exponent)}.${s.slice(-exponent)}`;
}

/** Field errors from a zod result, keyed by path ("lines.0.unitPrice"). */
export function fieldErrors(issues: readonly { path: (string | number)[]; message: string }[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const i of issues) out[i.path.join('.')] ??= i.message;
  return out;
}

/** What a person may do with an invoice, from its state and their side of it. */
export function invoiceActions(inv: InvoiceView, me: { orgId: string; held: readonly string[]; platform: boolean }) {
  const seller = !me.platform && inv.seller.tenantId === me.orgId;
  const buyer = !me.platform && inv.buyer.tenantId === me.orgId;
  const open = inv.status === 'ISSUED' || inv.status === 'PARTIALLY_PAID';
  const noMoneyYet = inv.payments.every((p) => p.status === 'FAILED');
  return {
    issue: seller && inv.status === 'DRAFT' && me.held.includes('invoice.create'),
    pay: buyer && open && BigInt(inv.balanceMinor) > 0n && me.held.includes('invoice.pay'),
    confirm: seller && me.held.includes('invoice.collect')
      ? inv.payments.filter((p) => p.method === 'BANK_TRANSFER' && p.status === 'AWAITING_CONFIRMATION')
      : [],
    void: seller && (inv.status === 'DRAFT' || (open && noMoneyYet)) && me.held.includes('invoice.void'),
  };
}

/* ─────────────────────────────────── hooks ─────────────────────────────────── */

export const invoiceKeys = {
  all: (orgId: string) => ['invoices', orgId] as const,
  list: (orgId: string, q: ListQuery) => ['invoices', orgId, 'list', q] as const,
  totals: (orgId: string, direction: Direction) => ['invoices', orgId, 'totals', direction] as const,
  detail: (orgId: string, id: string) => ['invoices', orgId, 'detail', id] as const,
  options: (orgId: string, id: string) => ['invoices', orgId, 'options', id] as const,
  counterparties: (orgId: string) => ['invoices', orgId, 'counterparties'] as const,
};

export interface ListQuery {
  direction: Direction;
  status: StatusFilter;
  q: string;
  page: number;
  size: number;
}

/** Open/overdue: what falls due first; everything else: newest first. */
export function sortFor(status: StatusFilter): string {
  return status === 'OPEN' || status === 'OVERDUE' ? 'dueDate,asc' : 'createdAt,desc';
}

export function useInvoices(port: WidgetDataPortLike, orgId: string, q: ListQuery) {
  return useQuery({
    queryKey: invoiceKeys.list(orgId, q),
    queryFn: () => port.request<InvoicePage>('billing.invoices.list', {
      tenantId: orgId, direction: q.direction, page: String(q.page), size: String(q.size), sort: sortFor(q.status),
      ...(q.status === 'ALL' ? {} : { status: q.status }), ...(q.q.trim() ? { q: q.q.trim() } : {}),
    }),
    enabled: orgId !== '',
    placeholderData: (prev) => prev,
  });
}

export function useTotals(port: WidgetDataPortLike, orgId: string, direction: Direction) {
  return useQuery({
    queryKey: invoiceKeys.totals(orgId, direction),
    queryFn: () => port.request<InvoiceTotals>('billing.invoices.summary', { tenantId: orgId, direction }),
    enabled: orgId !== '',
  });
}

export function useInvoice(port: WidgetDataPortLike, orgId: string, id: string | null) {
  return useQuery({
    queryKey: invoiceKeys.detail(orgId, id ?? ''),
    queryFn: () => port.request<InvoiceView>('billing.invoices.get', { tenantId: orgId, invoiceId: id ?? '' }),
    enabled: !!id && orgId !== '',
    // a payment in flight settles on the rail: poll briefly, hard-capped (ui-std §7)
    refetchInterval: (query) => (query.state.data?.payments.some((p) => p.status === 'PENDING') && query.state.dataUpdateCount < 10 ? 3_000 : false),
  });
}

export function usePaymentOptions(port: WidgetDataPortLike, orgId: string, id: string | null, enabled: boolean) {
  return useQuery({
    queryKey: invoiceKeys.options(orgId, id ?? ''),
    queryFn: () => port.request<PaymentOption[]>('billing.invoices.options', { tenantId: orgId, invoiceId: id ?? '' }),
    enabled: enabled && !!id && orgId !== '',
  });
}

export function useCounterparties(port: WidgetDataPortLike, orgId: string, enabled: boolean) {
  return useQuery({
    queryKey: invoiceKeys.counterparties(orgId),
    queryFn: () => port.request<Counterparty[]>('billing.invoices.counterparties', { tenantId: orgId }),
    enabled: enabled && orgId !== '',
  });
}

export type InvoiceCommand =
  | { kind: 'create'; values: InvoiceValues }
  | { kind: 'issue'; invoiceId: string; version: number }
  | { kind: 'pay'; invoiceId: string; version: number; method: PaymentMethod; amountMinor: string }
  | { kind: 'confirm'; invoiceId: string; paymentId: string }
  | { kind: 'void'; invoiceId: string; version: number; reason: string };

/** Every invoice write. Results are the server's: an InvoiceView, or a PaymentView for `pay`. */
export function useInvoiceCommand(port: WidgetDataPortLike, orgId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (c: InvoiceCommand): Promise<InvoiceView | PaymentView> => {
      const at = { tenantId: orgId };
      switch (c.kind) {
        case 'create':
          return port.command<InvoiceView>('billing.invoices.create', toCreateBody(c.values), at);
        case 'issue':
          return port.command<InvoiceView>('billing.invoices.issue', { expectedVersion: c.version }, { ...at, invoiceId: c.invoiceId });
        case 'pay':
          return port.command<PaymentView>('billing.invoices.pay',
            { method: c.method, amountMinor: c.amountMinor, expectedVersion: c.version }, { ...at, invoiceId: c.invoiceId });
        case 'confirm':
          return port.command<InvoiceView>('billing.invoices.confirm', {}, { ...at, invoiceId: c.invoiceId, paymentId: c.paymentId });
        case 'void':
          return port.command<InvoiceView>('billing.invoices.void', { reason: c.reason, expectedVersion: c.version }, { ...at, invoiceId: c.invoiceId });
      }
    },
    retry: 0,
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: invoiceKeys.all(orgId) });
      void qc.invalidateQueries({ queryKey: ['approvals'] });
    },
  });
}
