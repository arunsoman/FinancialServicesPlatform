/**
 * billing.invoices data contributions — core invoicing API (NEW in stage 2, shapes below are the
 * contract). Collections follow AGENTS §2f (paged envelope, server-side filter/search/sort);
 * money is minor-unit strings; writes carry expectedVersion (optimistic concurrency) and
 * port.command adds the Idempotency-Key + CSRF.
 *
 * The fixtures PLAY the server (stateful): draft → issued → part paid → paid, wallet payments
 * settle instantly, bank transfers wait for the seller to confirm receipt, cards hand back the
 * PSP's hosted page, and a void of an issued invoice waits for a second person.
 */
import type { DataSources } from '../../core/sources';
import type { FixtureRequest, FixtureSet } from '../../core/ports';
import { ApiError } from '../../../web-core/api';
import { forbidUnless } from '../../kit/forbid';
import { WB_TENANT } from '../../kit/fixture-ledger';
import { daysBetween, dueDateFor, lineAmounts, todayIso } from './model';
import type {
  Counterparty, Direction, InvoicePage, InvoiceStatus, InvoiceSummary, InvoiceTotals, InvoiceView, PaymentMethod,
  PaymentOption, PaymentView,
} from './model';

export const sources: DataSources = {
  'billing.invoices.list': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/invoices', query: ['direction', 'status', 'q', 'page', 'size', 'sort'] } },
  'billing.invoices.summary': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/invoices/summary', query: ['direction'] } },
  'billing.invoices.get': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/invoices/{invoiceId}' } },
  'billing.invoices.options': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/invoices/{invoiceId}/payment-options' } },
  'billing.invoices.counterparties': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/invoices/counterparties' } },
};

export const commands: DataSources = {
  'billing.invoices.create': { '*': { method: 'POST', path: '/api/tenants/{tenantId}/invoices' } },
  'billing.invoices.issue': { '*': { method: 'POST', path: '/api/tenants/{tenantId}/invoices/{invoiceId}/issue' } },
  'billing.invoices.pay': { '*': { method: 'POST', path: '/api/tenants/{tenantId}/invoices/{invoiceId}/payments' } },
  'billing.invoices.confirm': { '*': { method: 'POST', path: '/api/tenants/{tenantId}/invoices/{invoiceId}/payments/{paymentId}/confirm' } },
  'billing.invoices.void': { '*': { method: 'POST', path: '/api/tenants/{tenantId}/invoices/{invoiceId}/void' } },
};

/* ───────────────────────────── fixture world (fake, Namibian) ───────────────────────────── */

const ME = { tenantId: WB_TENANT, name: 'Namib Distributors (Pty) Ltd' };
const BUYERS: Counterparty[] = [
  { tenantId: '00000000-0000-0000-0000-0000000000b1', name: 'Swakop Wholesale CC', subType: 'WHOLESALER', currency: 'NAD' },
  { tenantId: '00000000-0000-0000-0000-0000000000b2', name: 'Oshana Traders', subType: 'WHOLESALER', currency: 'NAD' },
  { tenantId: '00000000-0000-0000-0000-0000000000b3', name: 'Kavango Fresh Market', subType: 'MERCHANT', currency: 'NAD' },
];
const SUPPLIER = { tenantId: '00000000-0000-0000-0000-0000000000c1', name: 'Windhoek Manufacturing Ltd' };

const TODAY = todayIso();
const ago = (days: number) => dueDateFor(TODAY, -days);
const uuid = (tag: string, n: number) => `00000000-0000-0000-0000-${tag}${String(n).padStart(12 - tag.length, '0')}`;

interface Seed {
  n: number; seller: InvoiceView['seller']; buyer: InvoiceView['buyer']; status: InvoiceStatus; issuedDaysAgo: number | null;
  creditDays: number; lines: [string, number, string, number][]; paid?: [PaymentMethod, string][]; awaiting?: string;
}

function build(s: Seed): InvoiceView {
  const lines = s.lines.map(([description, quantity, unitPriceMinor, taxRateBps], i) => ({
    lineNo: i + 1, description, quantity, unitPriceMinor, taxRateBps, ...lineAmounts(quantity, unitPriceMinor, taxRateBps),
  }));
  const sub = lines.reduce((a, l) => a + BigInt(l.netMinor), 0n);
  const tax = lines.reduce((a, l) => a + BigInt(l.taxMinor), 0n);
  const issueDate = s.issuedDaysAgo === null ? null : ago(s.issuedDaysAgo);
  const payments: PaymentView[] = (s.paid ?? []).map(([method, amountMinor], i) => ({
    paymentId: uuid('9a', s.n * 10 + i), method, amountMinor, status: 'SUCCEEDED', reference: method === 'BANK_TRANSFER' ? `INV-2026-00${s.n}` : `PSP-${s.n}${i}`,
    createdAt: `${ago(3)}T09:00:00Z`, confirmedAt: `${ago(3)}T09:00:05Z`, redirectUrl: null,
  }));
  if (s.awaiting) {
    payments.push({ paymentId: uuid('9b', s.n), method: 'BANK_TRANSFER', amountMinor: s.awaiting, status: 'AWAITING_CONFIRMATION',
      reference: `INV-2026-00${s.n}`, createdAt: `${ago(1)}T14:30:00Z`, confirmedAt: null, redirectUrl: null });
  }
  const total = sub + tax;
  const paid = payments.filter((p) => p.status === 'SUCCEEDED').reduce((a, p) => a + BigInt(p.amountMinor), 0n);
  return {
    invoiceId: uuid('1a', s.n), number: s.status === 'DRAFT' ? null : `INV-2026-00${s.n}`,
    seller: s.seller, buyer: s.buyer, currency: 'NAD',
    issueDate, dueDate: issueDate ? dueDateFor(issueDate, s.creditDays) : null, creditDays: s.creditDays,
    plannedIssueDate: issueDate ?? TODAY,
    totalMinor: total.toString(), paidMinor: paid.toString(), balanceMinor: (total - paid).toString(),
    status: s.status, version: 3, reference: s.n === 2 ? 'PO-77812' : null, notes: null,
    subtotalMinor: sub.toString(), taxMinor: tax.toString(), lines, payments, voidApprovalId: null,
    createdAt: `${issueDate ?? TODAY}T08:00:00Z`,
  };
}

const b = (i: number) => ({ tenantId: BUYERS[i].tenantId, name: BUYERS[i].name });

function seedWorld(): InvoiceView[] {
  return [
    build({ n: 1, seller: ME, buyer: b(0), status: 'ISSUED', issuedDaysAgo: 41, creditDays: 30, lines: [['Maize meal 12.5 kg', 120, '18950', 1500]] }),
    build({ n: 2, seller: ME, buyer: b(1), status: 'PARTIALLY_PAID', issuedDaysAgo: 26, creditDays: 30,
      lines: [['Cooking oil 5 L', 60, '21500', 1500], ['Delivery — Oshakati route', 1, '85000', 1500]], paid: [['WALLET', '1000000']], awaiting: '250000' }),
    build({ n: 3, seller: ME, buyer: b(2), status: 'ISSUED', issuedDaysAgo: 4, creditDays: 30, lines: [['Fresh produce crates', 40, '32000', 0]] }),
    build({ n: 4, seller: ME, buyer: b(0), status: 'PAID', issuedDaysAgo: 50, creditDays: 30, lines: [['Sugar 10 kg', 80, '15900', 1500]], paid: [['BANK_TRANSFER', '1462800']] }),
    build({ n: 5, seller: ME, buyer: b(1), status: 'DRAFT', issuedDaysAgo: null, creditDays: 30, lines: [['Rice 10 kg', 50, '17400', 1500]] }),
    build({ n: 6, seller: SUPPLIER, buyer: ME, status: 'ISSUED', issuedDaysAgo: 18, creditDays: 30,
      lines: [['Maize meal 12.5 kg — factory lot', 400, '14200', 1500]] }),
    build({ n: 7, seller: SUPPLIER, buyer: ME, status: 'ISSUED', issuedDaysAgo: 35, creditDays: 30, lines: [['Packaging film rolls', 25, '61000', 1500]] }),
  ];
}

let world = seedWorld();
const WALLET_BALANCE = '4500000';

/** The fixture plays the server's rules — a broken rule is a 409/422, as core answers. */
function problem(status: number, code: string, detail: string): ApiError {
  return new ApiError({ status, code, title: code, detail });
}

function find(id: string): InvoiceView {
  const inv = world.find((x) => x.invoiceId === id);
  if (!inv) throw problem(404, 'INVOICE_NOT_FOUND', 'No such invoice here.');
  return inv;
}

function save(next: InvoiceView): InvoiceView {
  world = world.map((x) => (x.invoiceId === next.invoiceId ? next : x));
  return next;
}

function checkVersion(inv: InvoiceView, body: unknown) {
  const v = (body as { expectedVersion?: number }).expectedVersion;
  if (v !== inv.version) throw problem(409, 'STALE_VERSION', 'Someone changed this invoice — reload and try again.');
}

function settle(inv: InvoiceView): InvoiceView {
  const paid = inv.payments.filter((p) => p.status === 'SUCCEEDED').reduce((a, p) => a + BigInt(p.amountMinor), 0n);
  const balance = BigInt(inv.totalMinor) - paid;
  const status: InvoiceStatus = balance === 0n ? 'PAID' : paid > 0n ? 'PARTIALLY_PAID' : 'ISSUED';
  return { ...inv, paidMinor: paid.toString(), balanceMinor: balance.toString(), status, version: inv.version + 1 };
}

function side(inv: InvoiceSummary, direction: Direction): boolean {
  if (direction === 'SALES') return inv.seller.tenantId === ME.tenantId;
  if (direction === 'PURCHASES') return inv.buyer.tenantId === ME.tenantId && inv.status !== 'DRAFT';
  return inv.status !== 'DRAFT'; // platform: drafts are the seller's alone
}

function overdue(inv: InvoiceSummary): boolean {
  return (inv.status === 'ISSUED' || inv.status === 'PARTIALLY_PAID') && !!inv.dueDate && daysBetween(TODAY, inv.dueDate) < 0;
}

function summary(inv: InvoiceView): InvoiceSummary {
  const { invoiceId, number, seller, buyer, currency, issueDate, dueDate, creditDays, totalMinor, paidMinor, balanceMinor, status, version } = inv;
  return { invoiceId, number, seller, buyer, currency, issueDate, dueDate, creditDays, totalMinor, paidMinor, balanceMinor, status, version };
}

const sum = (rows: InvoiceSummary[]) => rows.reduce((a, r) => a + BigInt(r.balanceMinor), 0n).toString();

/* ─────────────────────────────────────── fixtures ─────────────────────────────────────── */

export const fixtures: FixtureSet = {
  'billing.invoices.list': {
    default: (req: FixtureRequest): InvoicePage => {
      forbidUnless(req.permissions, 'invoice.view');
      const direction = (req.params.direction ?? 'SALES') as Direction;
      const status = req.params.status;
      const q = (req.params.q ?? '').toLowerCase();
      const page = Number(req.params.page ?? '0');
      const size = Number(req.params.size ?? '20');
      const rows = world.filter((x) => side(x, direction))
        .filter((x) => !status || status === 'ALL'
          || (status === 'OPEN' ? x.status === 'ISSUED' || x.status === 'PARTIALLY_PAID'
            : status === 'OVERDUE' ? overdue(x)
              : status === 'VOID' ? x.status === 'VOID' || x.status === 'VOID_PENDING' : x.status === status))
        .filter((x) => !q || `${x.number ?? ''} ${x.buyer.name} ${x.seller.name} ${x.reference ?? ''}`.toLowerCase().includes(q))
        .sort((a, c) => (req.params.sort === 'dueDate,asc'
          ? (a.dueDate ?? '9999').localeCompare(c.dueDate ?? '9999')
          : c.createdAt.localeCompare(a.createdAt)))
        .map(summary);
      return {
        items: rows.slice(page * size, page * size + size), page, size, totalElements: rows.length,
        totalPages: Math.max(1, Math.ceil(rows.length / size)), hasNext: (page + 1) * size < rows.length,
      };
    },
    empty: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 1, hasNext: false } satisfies InvoicePage,
    error: null,
  },
  'billing.invoices.summary': {
    default: (req: FixtureRequest): InvoiceTotals => {
      forbidUnless(req.permissions, 'invoice.view');
      const direction = (req.params.direction ?? 'SALES') as Direction;
      const open = world.filter((x) => side(x, direction) && (x.status === 'ISSUED' || x.status === 'PARTIALLY_PAID'));
      const late = open.filter(overdue);
      const soon = open.filter((x) => !overdue(x) && x.dueDate && daysBetween(TODAY, x.dueDate) <= 7);
      return { direction, currency: 'NAD', outstandingMinor: sum(open), overdueMinor: sum(late), dueSoonMinor: sum(soon), openCount: open.length, overdueCount: late.length };
    },
    empty: null, // the empty scenario: nothing at all (the widget then shows no totals bar)
    error: null,
  },
  'billing.invoices.get': {
    default: (req: FixtureRequest): InvoiceView => {
      forbidUnless(req.permissions, 'invoice.view');
      return find(req.params.invoiceId ?? '');
    },
    error: null,
  },
  'billing.invoices.counterparties': {
    default: (req: FixtureRequest): Counterparty[] => {
      forbidUnless(req.permissions, 'invoice.create');
      return BUYERS;
    },
    empty: [],
    error: null,
  },
  'billing.invoices.options': {
    default: (req: FixtureRequest): PaymentOption[] => {
      forbidUnless(req.permissions, 'invoice.pay');
      const inv = find(req.params.invoiceId ?? '');
      const enough = BigInt(WALLET_BALANCE) >= BigInt(inv.balanceMinor);
      return [
        { method: 'WALLET', available: true, reason: enough ? null : 'Your wallet covers part of this — pay part now, the rest later.',
          walletBalanceMinor: WALLET_BALANCE, payee: null, reference: null },
        { method: 'BANK_TRANSFER', available: true, reason: null, walletBalanceMinor: null,
          payee: { name: inv.seller.name, bankName: 'Bank Windhoek', accountEnding: '42' }, reference: inv.number },
        { method: 'CARD', available: true, reason: null, walletBalanceMinor: null, payee: null, reference: null },
      ];
    },
    empty: [],
    error: null,
  },
  'billing.invoices.create': {
    default: (req: FixtureRequest): InvoiceView => {
      forbidUnless(req.permissions, 'invoice.create');
      const body = req.body as {
        buyerTenantId: string; currency: string; issueDate: string; creditDays: number; reference: string | null; notes: string | null;
        lines: { description: string; quantity: number; unitPriceMinor: string; taxRateBps: number }[];
      };
      const buyer = BUYERS.find((x) => x.tenantId === body.buyerTenantId);
      if (!buyer) throw problem(422, 'INVOICE_BUYER', 'You can only invoice organisations in your network.');
      const n = world.length + 1;
      const inv = build({ n, seller: ME, buyer: { tenantId: buyer.tenantId, name: buyer.name }, status: 'DRAFT', issuedDaysAgo: null,
        creditDays: body.creditDays, lines: body.lines.map((l) => [l.description, l.quantity, l.unitPriceMinor, l.taxRateBps]) });
      const draft: InvoiceView = { ...inv, currency: body.currency, plannedIssueDate: body.issueDate, reference: body.reference, notes: body.notes, version: 1 };
      world = [draft, ...world];
      return draft;
    },
    error: null,
  },
  'billing.invoices.issue': {
    default: (req: FixtureRequest): InvoiceView => {
      forbidUnless(req.permissions, 'invoice.create');
      const inv = find(req.params.invoiceId ?? '');
      checkVersion(inv, req.body);
      if (inv.status !== 'DRAFT') throw problem(409, 'INVOICE_STATE', 'Only a draft can be issued.');
      const seq = world.filter((x) => x.number).length + 1;
      return save({ ...inv, status: 'ISSUED', number: `INV-2026-00${seq}`, issueDate: inv.plannedIssueDate,
        dueDate: dueDateFor(inv.plannedIssueDate, inv.creditDays), version: inv.version + 1 });
    },
    error: null,
  },
  'billing.invoices.pay': {
    default: (req: FixtureRequest): PaymentView => {
      forbidUnless(req.permissions, 'invoice.pay');
      const inv = find(req.params.invoiceId ?? '');
      checkVersion(inv, req.body);
      const body = req.body as { method: PaymentMethod; amountMinor: string };
      if (inv.status !== 'ISSUED' && inv.status !== 'PARTIALLY_PAID') throw problem(409, 'INVOICE_STATE', 'This invoice is not open for payment.');
      if (BigInt(body.amountMinor) > BigInt(inv.balanceMinor)) throw problem(422, 'PAYMENT_EXCEEDS_BALANCE', 'That is more than the balance due.');
      if (body.method === 'WALLET' && BigInt(body.amountMinor) > BigInt(WALLET_BALANCE)) {
        throw problem(422, 'WALLET_INSUFFICIENT', 'Your wallet balance does not cover that amount.');
      }
      const id = uuid('9c', inv.payments.length + Number(inv.invoiceId.slice(-2)) * 10);
      const pay: PaymentView = {
        paymentId: id, method: body.method, amountMinor: body.amountMinor,
        status: body.method === 'WALLET' ? 'SUCCEEDED' : body.method === 'BANK_TRANSFER' ? 'AWAITING_CONFIRMATION' : 'PENDING',
        reference: body.method === 'BANK_TRANSFER' ? inv.number : `PSP-${id.slice(-6)}`,
        createdAt: new Date().toISOString(), confirmedAt: body.method === 'WALLET' ? new Date().toISOString() : null,
        // the PSP's hosted page (fixture: a placeholder path; the real one comes from the PSP plugin)
        redirectUrl: body.method === 'CARD' ? `/pay/card/${id}` : null,
      };
      const next = { ...inv, payments: [...inv.payments, pay] };
      save(body.method === 'WALLET' ? settle(next) : { ...next, version: inv.version + 1 });
      return pay;
    },
    error: null,
  },
  'billing.invoices.confirm': {
    default: (req: FixtureRequest): InvoiceView => {
      forbidUnless(req.permissions, 'invoice.collect');
      const inv = find(req.params.invoiceId ?? '');
      const p = inv.payments.find((x) => x.paymentId === req.params.paymentId);
      if (!p || p.status !== 'AWAITING_CONFIRMATION') throw problem(409, 'PAYMENT_STATE', 'This payment is not waiting for confirmation.');
      return save(settle({ ...inv, payments: inv.payments.map((x) => (x === p ? { ...x, status: 'SUCCEEDED', confirmedAt: new Date().toISOString() } : x)) }));
    },
    error: null,
  },
  'billing.invoices.void': {
    default: (req: FixtureRequest): InvoiceView => {
      forbidUnless(req.permissions, 'invoice.void');
      const inv = find(req.params.invoiceId ?? '');
      checkVersion(inv, req.body);
      if (inv.status === 'DRAFT') return save({ ...inv, status: 'VOID', version: inv.version + 1 });
      if (inv.status !== 'ISSUED' || inv.payments.some((p) => p.status !== 'FAILED')) {
        throw problem(409, 'INVOICE_STATE', 'An invoice with payments cannot be voided — issue a credit note instead.');
      }
      // issued: reversing the receivable is four-eyes — it waits for another person
      return save({ ...inv, status: 'VOID_PENDING', voidApprovalId: uuid('ap', 1), version: inv.version + 1 });
    },
    error: null,
  },
};

/** Tests reset the stateful fixture world between cases. */
export function resetInvoiceFixtures(): void {
  world = seedWorld();
}

export const INVOICE_FIXTURE_ORG = WB_TENANT;
export const INVOICE_FIXTURE_BUYERS = BUYERS;
