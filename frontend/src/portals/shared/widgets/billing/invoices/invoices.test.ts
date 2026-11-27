import { beforeEach, describe, expect, it } from 'vitest';
import type { FixtureRequest } from '../../core/ports';
import widget from './index';
import { INVOICE_FIXTURE_BUYERS, commands, fixtures, resetInvoiceFixtures, sources } from './data';
import {
  DEFAULT_CREDIT_DAYS, blankInvoice, daysBetween, dueDateFor, dueState, fieldErrors, filtersFor, invoiceActions, invoiceSchema,
  lineAmounts, payAmountError, previewTotals, toCreateBody, toMajorText, toMinor, todayIso,
} from './model';
import type { InvoicePage, InvoiceTotals, InvoiceView, PaymentOption, PaymentView } from './model';

const ALL = ['invoice.view', 'invoice.create', 'invoice.pay', 'invoice.collect', 'invoice.void'];
const call = <T,>(name: string, params: Record<string, string> = {}, body: unknown = {}, permissions = ALL): T => {
  const answer = (fixtures[name] as { default: (r: FixtureRequest) => unknown }).default;
  return answer({ params, body, permissions, scenario: 'default' }) as T;
};

describe('manifest', () => {
  it('declares id, the read permission, all four tiers', () => {
    expect(widget.id).toBe('billing.invoices');
    expect(widget.requires).toEqual(['invoice.view']);
    for (const t of ['xs', 'sm', 'md', 'lg'] as const) expect(widget.size[t]).toBeDefined();
  });

  it('gates every action on its own permission and maps it to an operation', () => {
    const cells = Object.fromEntries((widget.actions ?? []).map((a) => [a.id, a.cell]));
    expect(cells).toEqual({
      'invoice.create': 'invoice.create', 'invoice.issue': 'invoice.create', 'invoice.pay': 'invoice.pay',
      'invoice.collect': 'invoice.collect', 'invoice.void': 'invoice.void',
    });
    for (const a of widget.actions ?? []) expect(widget.commandOperations?.[a.command ?? '']).toBeDefined();
    expect(widget.commandOperations?.['billing.invoices.void']).toBe('approve'); // four-eyes for issued invoices
  });

  it('maps every source and command, GET for reads and POST for writes, under /api/tenants/{tenantId}/invoices', () => {
    for (const s of widget.sources ?? []) {
      expect(sources[s]?.['*']?.method).toBe('GET');
      expect(sources[s]?.['*']?.path.startsWith('/api/tenants/{tenantId}/invoices')).toBe(true);
      expect(fixtures[s]).toBeDefined();
    }
    for (const c of widget.commands ?? []) {
      expect(commands[c]?.['*']?.method).toBe('POST');
      expect(fixtures[c]).toBeDefined();
    }
  });
});

describe('credit terms', () => {
  it('defaults to 30 days and dates the due date issue + 30', () => {
    expect(DEFAULT_CREDIT_DAYS).toBe(30);
    expect(blankInvoice('2026-11-23').creditDays).toBe(30);
    expect(dueDateFor('2026-11-23', 30)).toBe('2026-12-23');
    expect(dueDateFor('2026-01-31', 30)).toBe('2026-03-02'); // calendar days, not "same day next month"
    expect(dueDateFor('2026-12-15', 30)).toBe('2027-01-14');
  });

  it('says how the invoice stands on a given day', () => {
    const open = (dueDate: string) => ({ status: 'ISSUED' as const, dueDate, creditDays: 30 });
    expect(dueState(open('2026-12-23'), '2026-11-23')).toEqual({ label: 'Due in 30 days', tone: 'info' });
    expect(dueState(open('2026-11-28'), '2026-11-23')).toEqual({ label: 'Due in 5 days', tone: 'warn' });
    expect(dueState(open('2026-11-23'), '2026-11-23')).toEqual({ label: 'Due today', tone: 'warn' });
    expect(dueState(open('2026-11-22'), '2026-11-23')).toEqual({ label: 'Overdue by 1 day', tone: 'bad' });
    expect(dueState({ status: 'PAID', dueDate: '2026-01-01', creditDays: 30 }, '2026-11-23').label).toBe('Paid');
    expect(dueState({ status: 'DRAFT', dueDate: null, creditDays: 30 }, '2026-11-23').label).toBe('Net 30 once issued');
    expect(daysBetween('2026-11-23', '2026-11-20')).toBe(-3);
  });

  it('never shows a buyer the seller\'s drafts filter', () => {
    expect(filtersFor('PURCHASES')).not.toContain('DRAFT');
    expect(filtersFor('ALL')).not.toContain('DRAFT');
    expect(filtersFor('SALES')).toContain('DRAFT');
  });
});

describe('money (exact, minor units)', () => {
  it('parses major text without floats', () => {
    expect(toMinor('189.50')).toBe('18950');
    expect(toMinor('1,250')).toBe('125000');
    expect(toMinor('0.1')).toBe('10');
    expect(toMinor('0')).toBeNull();
    expect(toMinor('1.234')).toBeNull();
    expect(toMinor('abc')).toBeNull();
    expect(toMajorText('18950')).toBe('189.50');
    expect(toMajorText('5')).toBe('0.05');
  });

  it('computes VAT half-up to the cent', () => {
    expect(lineAmounts(120, '18950', 1500)).toEqual({ netMinor: '2274000', taxMinor: '341100', totalMinor: '2615100' });
    expect(lineAmounts(1, '3', 1500).taxMinor).toBe('0');   // 0.45 → 0
    expect(lineAmounts(1, '10', 1500).taxMinor).toBe('2');  // 1.5 → 2 (half-up)
    expect(lineAmounts(3, '999999999999', 0).totalMinor).toBe('2999999999997'); // BigInt, no float loss
  });

  it('previews totals and skips half-typed lines', () => {
    const t = previewTotals([
      { description: 'a', quantity: '2', unitPrice: '100.00', taxRateBps: '1500' },
      { description: 'b', quantity: '', unitPrice: '50', taxRateBps: '0' },
    ]);
    expect(t).toEqual({ subtotalMinor: '20000', taxMinor: '3000', totalMinor: '23000' });
  });

  it('refuses a payment above the balance', () => {
    expect(payAmountError('100.00', '10000')).toBeNull();
    expect(payAmountError('100.01', '10000')).toMatch(/more than the balance/);
    expect(payAmountError('0', '10000')).toMatch(/above zero/);
  });
});

describe('form', () => {
  it('puts each error on its field', () => {
    const r = invoiceSchema.safeParse({ ...blankInvoice('2026-11-23'), lines: [{ description: '', quantity: '0', unitPrice: 'x', taxRateBps: '1500' }] });
    expect(r.success).toBe(false);
    const e = r.success ? {} : fieldErrors(r.error.issues);
    expect(e.buyerTenantId).toMatch(/who you are invoicing/);
    expect(e['lines.0.description']).toMatch(/Describe/);
    expect(e['lines.0.quantity']).toMatch(/Whole number/);
    expect(e['lines.0.unitPrice']).toMatch(/price above zero/);
  });

  it('sends minor units, integer quantities and the credit days', () => {
    const v = { ...blankInvoice('2026-11-23'), buyerTenantId: 'b1', reference: '', lines: [{ description: ' Sugar ', quantity: '3', unitPrice: '15.90', taxRateBps: '1500' as const }] };
    expect(toCreateBody(v)).toEqual({
      buyerTenantId: 'b1', currency: 'NAD', issueDate: '2026-11-23', creditDays: 30, reference: null, notes: null,
      lines: [{ description: 'Sugar', quantity: 3, unitPriceMinor: '1590', taxRateBps: 1500 }],
    });
  });
});

describe('the fixture server (plays core\'s invoice rules)', () => {
  beforeEach(() => resetInvoiceFixtures());
  const org = '00000000-0000-0000-0000-0000000000a1';

  it('lists sales and purchases apart, and hides other sellers\' drafts from buyers', () => {
    const sales = call<InvoicePage>('billing.invoices.list', { direction: 'SALES' });
    const purchases = call<InvoicePage>('billing.invoices.list', { direction: 'PURCHASES' });
    expect(sales.items.every((i) => i.seller.tenantId === org)).toBe(true);
    expect(purchases.items.every((i) => i.buyer.tenantId === org && i.status !== 'DRAFT')).toBe(true);
    expect(call<InvoicePage>('billing.invoices.list', { direction: 'SALES', status: 'OVERDUE' }).items.length).toBeGreaterThan(0);
  });

  it('forbids without invoice.view', () => {
    expect(() => call('billing.invoices.list', {}, {}, [])).toThrow(/invoice.view/);
  });

  it('draft → issue sets the due date to issue + 30 days', () => {
    const draft = call<InvoiceView>('billing.invoices.create', {}, {
      buyerTenantId: INVOICE_FIXTURE_BUYERS[0].tenantId, currency: 'NAD', issueDate: todayIso(), creditDays: 30, reference: null, notes: null,
      lines: [{ description: 'Sugar', quantity: 2, unitPriceMinor: '1000', taxRateBps: 1500 }],
    });
    expect(draft.status).toBe('DRAFT');
    expect(draft.totalMinor).toBe('2300');
    const issued = call<InvoiceView>('billing.invoices.issue', { invoiceId: draft.invoiceId }, { expectedVersion: draft.version });
    expect(issued.status).toBe('ISSUED');
    expect(issued.dueDate).toBe(dueDateFor(todayIso(), 30));
    expect(() => call('billing.invoices.issue', { invoiceId: draft.invoiceId }, { expectedVersion: draft.version })).toThrow(/changed/);
  });

  it('wallet pays instantly, bank transfer waits for the seller, card hands back the hosted page', () => {
    const open = call<InvoicePage>('billing.invoices.list', { direction: 'PURCHASES' }).items[0];
    const opts = call<PaymentOption[]>('billing.invoices.options', { invoiceId: open.invoiceId });
    expect(opts.map((o) => o.method)).toEqual(['WALLET', 'BANK_TRANSFER', 'CARD']);
    expect(opts[1].payee?.accountEnding).toHaveLength(2); // never a full account number

    const wallet = call<PaymentView>('billing.invoices.pay', { invoiceId: open.invoiceId }, { method: 'WALLET', amountMinor: '100000', expectedVersion: open.version });
    expect(wallet.status).toBe('SUCCEEDED');
    const after = call<InvoiceView>('billing.invoices.get', { invoiceId: open.invoiceId });
    expect(after.status).toBe('PARTIALLY_PAID');
    expect(BigInt(after.balanceMinor)).toBe(BigInt(open.balanceMinor) - 100000n);

    const eft = call<PaymentView>('billing.invoices.pay', { invoiceId: open.invoiceId }, { method: 'BANK_TRANSFER', amountMinor: '1000', expectedVersion: after.version });
    expect(eft.status).toBe('AWAITING_CONFIRMATION');
    expect(call<InvoiceView>('billing.invoices.get', { invoiceId: open.invoiceId }).balanceMinor).toBe(after.balanceMinor); // not money until confirmed
    const confirmed = call<InvoiceView>('billing.invoices.confirm', { invoiceId: open.invoiceId, paymentId: eft.paymentId });
    expect(BigInt(confirmed.balanceMinor)).toBe(BigInt(after.balanceMinor) - 1000n);

    const card = call<PaymentView>('billing.invoices.pay', { invoiceId: open.invoiceId }, { method: 'CARD', amountMinor: '500', expectedVersion: confirmed.version });
    expect(card.status).toBe('PENDING');
    expect(card.redirectUrl).toBeTruthy();
  });

  it('refuses paying more than the balance', () => {
    const open = call<InvoicePage>('billing.invoices.list', { direction: 'PURCHASES' }).items[0];
    const tooMuch = (BigInt(open.balanceMinor) + 1n).toString();
    expect(() => call('billing.invoices.pay', { invoiceId: open.invoiceId }, { method: 'CARD', amountMinor: tooMuch, expectedVersion: open.version })).toThrow(/more than the balance/);
  });

  it('a draft is discarded at once; an issued invoice waits for a second person to void', () => {
    const sales = call<InvoicePage>('billing.invoices.list', { direction: 'SALES' }).items;
    const draft = sales.find((i) => i.status === 'DRAFT')!;
    expect(call<InvoiceView>('billing.invoices.void', { invoiceId: draft.invoiceId }, { reason: 'dup', expectedVersion: draft.version }).status).toBe('VOID');
    const issued = sales.find((i) => i.status === 'ISSUED')!;
    const v = call<InvoiceView>('billing.invoices.void', { invoiceId: issued.invoiceId }, { reason: 'wrong buyer', expectedVersion: issued.version });
    expect(v.status).toBe('VOID_PENDING');
    expect(v.voidApprovalId).toBeTruthy();
  });

  it('totals are the server\'s (overdue is part of outstanding)', () => {
    const t = call<InvoiceTotals>('billing.invoices.summary', { direction: 'SALES' });
    expect(BigInt(t.overdueMinor) <= BigInt(t.outstandingMinor)).toBe(true);
    expect(t.overdueCount).toBeGreaterThan(0);
  });
});

describe('who may do what', () => {
  const inv = (over: Partial<InvoiceView>): InvoiceView => ({
    invoiceId: 'i', number: 'INV-1', seller: { tenantId: 'S', name: 's' }, buyer: { tenantId: 'B', name: 'b' }, currency: 'NAD',
    issueDate: '2026-11-01', dueDate: '2026-12-01', creditDays: 30, totalMinor: '100', paidMinor: '0', balanceMinor: '100',
    status: 'ISSUED', version: 1, plannedIssueDate: '2026-11-01', reference: null, notes: null, subtotalMinor: '100', taxMinor: '0',
    lines: [], payments: [], voidApprovalId: null, createdAt: '', ...over,
  });

  it('the buyer pays, the seller issues/voids/confirms, the platform only reads', () => {
    expect(invoiceActions(inv({}), { orgId: 'B', held: ALL, platform: false }).pay).toBe(true);
    expect(invoiceActions(inv({}), { orgId: 'S', held: ALL, platform: false }).pay).toBe(false);
    expect(invoiceActions(inv({ status: 'DRAFT' }), { orgId: 'S', held: ALL, platform: false }).issue).toBe(true);
    expect(invoiceActions(inv({}), { orgId: 'S', held: ALL, platform: false }).void).toBe(true);
    expect(invoiceActions(inv({}), { orgId: 'S', held: ALL, platform: true })).toEqual({ issue: false, pay: false, confirm: [], void: false });
    expect(invoiceActions(inv({}), { orgId: 'B', held: ['invoice.view'], platform: false }).pay).toBe(false);
  });

  it('an invoice with money on it cannot be voided', () => {
    const paid = inv({ status: 'PARTIALLY_PAID', payments: [{ paymentId: 'p', method: 'WALLET', amountMinor: '10', status: 'SUCCEEDED', reference: null, createdAt: '', confirmedAt: '', redirectUrl: null }] });
    expect(invoiceActions(paid, { orgId: 'S', held: ALL, platform: false }).void).toBe(false);
  });
});
