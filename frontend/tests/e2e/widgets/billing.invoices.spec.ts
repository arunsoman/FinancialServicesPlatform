/**
 * billing.invoices — end-to-end spec (fsp-widget skill).
 *
 *   workbench lane — fixtures, every scenario × every tier, geometry, permission gating, and the
 *                    journeys: 30-day draft → issue, pay by wallet / bank transfer / card, seller
 *                    confirms a transfer, void (draft at once, issued → four-eyes), platform read-only
 *   portal lane    — real BFF sign-in + the real screen; NEW operations answered from fixtures
 *                    until FSP_WIDGET_LIVE=1 (stage 2: nothing stubbed)
 *
 * Run: bun run widgets:e2e billing.invoices [--lane workbench|portal|all] [--live]
 */
import { expect, test, type Locator, type Page } from '@playwright/test';
import {
  LIVE, expectGatedWithoutCells, openScreen, openWorkbench, portalSession, stageOf, stubPlannedOps, sweepScenarios,
} from '../widget-support';
import { fixtures } from '../../../src/portals/shared/widgets/billing/invoices/data';
import { COOLDOWN_MS } from '../../../src/portals/shared/web-core/submit-guard';

const ID = 'billing.invoices';
const LANES = (process.env.FSP_WIDGET_LANES ?? 'workbench,portal').split(',');
const ACTIONS = ['invoice.create', 'invoice.issue', 'invoice.pay', 'invoice.collect', 'invoice.void'];
const ALL_CELLS = ['invoice.view', 'invoice.create', 'invoice.pay', 'invoice.collect', 'invoice.void'];

/** An org portal (sales + purchases); the workbench default (PLATFORM) is the read-only admin view. */
async function org(page: Page, cells: string[] = ALL_CELLS): Promise<Locator> {
  await openWorkbench(page, ID, { tier: 'md', cells, portal: 'DISTRIBUTOR' });
  const w = page.locator('[data-tier="md"]');
  await expect(w.getByRole('heading', { name: 'Invoices' })).toBeVisible();
  return w;
}

const row = (w: Locator, number: string) => w.locator(`[data-invoice="${number}"]`);

test.describe(`${ID} · workbench (fixtures)`, () => {
  test.skip(!LANES.includes('workbench'), 'workbench lane not selected');

  test('every scenario renders its state at every tier, without layout faults', async ({ page }) => {
    await sweepScenarios(page, ID);
  });

  test('without its cells the widget is forbidden and no action is reachable', async ({ page }) => {
    await expectGatedWithoutCells(page, ID, ACTIONS);
  });

  test('each action needs its own cell', async ({ page }) => {
    // invoice.view only: list and detail, no write anywhere
    let w = await org(page, ['invoice.view']);
    await expect(w.locator('[data-action]')).toHaveCount(0);
    await w.getByRole('tab', { name: 'Purchases' }).click();
    await expect(w.locator('[data-action]')).toHaveCount(0);
    // create + void for the seller's draft
    w = await org(page, ['invoice.view', 'invoice.create', 'invoice.void']);
    await expect(w.locator('[data-action="invoice.create"]').first()).toBeVisible();
    await expect(w.locator('[data-action="invoice.issue"]')).toBeVisible(); // the draft is shown first
    await expect(w.locator('[data-action="invoice.void"]')).toBeVisible();
  });

  test('new invoice: 30-day credit by default, due date shown, validation on the field, draft → issue', async ({ page }) => {
    const w = await org(page);
    await w.locator('[data-action="invoice.create"]').first().click();
    const form = w.getByRole('form', { name: 'New invoice' });
    await expect(form.getByLabel('Credit terms')).toHaveValue('30');
    await expect(form.getByText(/Payment due \d{4}-\d{2}-\d{2}/)).toBeVisible();

    await form.getByRole('button', { name: 'Save draft' }).click();
    await expect(form.getByText('Choose who you are invoicing')).toBeVisible();
    await expect(form.getByText('Describe the item')).toBeVisible();

    await page.waitForTimeout(COOLDOWN_MS + 50); // the double-press guard: a person fixes the fields first
    await form.getByLabel('Bill to').selectOption({ label: 'Kavango Fresh Market · merchant' });
    await form.getByLabel('Item or service').fill('Bottled water 24-pack');
    await form.getByLabel('Qty').fill('10');
    await form.getByLabel('Unit price (NAD)').fill('120.00');
    await expect(form.getByLabel('Invoice total preview')).toContainText('1,380.00'); // 1,200 + 15% VAT
    await form.getByLabel('Credit terms').selectOption('45');
    await expect(form.getByText(/Net 45 — due/)).toBeVisible();

    await form.locator('[data-action="invoice.issue"]').click();
    await expect(w.getByText('Issued — the buyer can see and pay it now.')).toBeVisible();
    const detail = w.getByRole('region', { name: 'Invoice' });
    await expect(detail.getByText('Net 45')).toBeVisible();
    await expect(detail.getByText('Due in 45 days')).toBeVisible();
  });

  test('buyer pays by wallet (confirmed in place), the balance drops and the status is part paid', async ({ page }) => {
    const w = await org(page);
    await w.getByRole('tab', { name: 'Purchases' }).click();
    await row(w, 'INV-2026-006').click();
    await w.locator('[data-action="invoice.pay"]').click();
    const pay = w.getByRole('region', { name: 'Pay this invoice' });
    await pay.locator('[data-method="WALLET"]').click();
    await pay.getByLabel('Amount (NAD)').fill('1000.00');
    await pay.locator('[data-action="invoice.pay"]').click();
    await expect(pay.getByText(/Pay 1000.00 NAD from your wallet\?/)).toBeVisible();
    await pay.getByRole('button', { name: 'Pay now' }).click();
    await expect(w.getByText('Paid — the invoice balance is updated.')).toBeVisible();
    await expect(row(w, 'INV-2026-006')).toContainText('64,320.00');
    await expect(row(w, 'INV-2026-006')).toContainText('Part paid');
  });

  test('pay refuses more than the balance, on the field', async ({ page }) => {
    const w = await org(page);
    await w.getByRole('tab', { name: 'Purchases' }).click();
    await row(w, 'INV-2026-007').click();
    await w.locator('[data-action="invoice.pay"]').click();
    const pay = w.getByRole('region', { name: 'Pay this invoice' });
    await pay.getByLabel('Amount (NAD)').fill('99999999.00');
    await pay.getByLabel('Amount (NAD)').blur();
    await expect(pay.getByText('That is more than the balance due')).toBeVisible();
  });

  test('bank transfer shows who to pay (masked) + reference; it is not money until the seller confirms', async ({ page }) => {
    const w = await org(page);
    await w.getByRole('tab', { name: 'Purchases' }).click();
    await row(w, 'INV-2026-007').click();
    await w.locator('[data-action="invoice.pay"]').click();
    const pay = w.getByRole('region', { name: 'Pay this invoice' });
    await pay.locator('[data-method="BANK_TRANSFER"]').click();
    const eft = pay.getByLabel('Bank transfer details');
    await expect(eft).toContainText('account ending 42');
    await expect(eft).toContainText('INV-2026-007');
    await pay.getByRole('button', { name: 'I’ve made the transfer' }).click();
    await expect(w.getByText('Transfer recorded — the seller confirms when the money arrives.')).toBeVisible();
    await expect(w.getByRole('region', { name: 'Payments' })).toContainText('Awaiting seller confirmation');
  });

  test('card goes to the provider’s hosted page — no card fields in the portal', async ({ page }) => {
    const w = await org(page);
    await w.getByRole('tab', { name: 'Purchases' }).click();
    await row(w, 'INV-2026-007').click();
    await w.locator('[data-action="invoice.pay"]').click();
    const pay = w.getByRole('region', { name: 'Pay this invoice' });
    await pay.locator('[data-method="CARD"]').click();
    await expect(pay.getByLabel(/card number/i)).toHaveCount(0);
    await pay.getByRole('button', { name: 'Continue to secure card page' }).click();
    await expect(w.getByText('Finish the card payment on the secure card page.')).toBeVisible();
    await expect(w.getByRole('link', { name: 'Open the secure card page' })).toBeVisible();
  });

  test('seller confirms a bank transfer; the balance updates', async ({ page }) => {
    const w = await org(page);
    await row(w, 'INV-2026-002').click();
    await expect(w.getByRole('region', { name: 'Payments' })).toContainText('Awaiting seller confirmation');
    await w.locator('[data-action="invoice.collect"]').click();
    await expect(w.getByText('Payment confirmed — the balance is updated.')).toBeVisible();
    await expect(row(w, 'INV-2026-002')).toContainText('3,312.50');
  });

  test('void: an issued invoice waits for a second person; the reason is required', async ({ page }) => {
    const w = await org(page);
    await row(w, 'INV-2026-003').click();
    await w.locator('[data-action="invoice.void"]').click();
    await w.getByRole('button', { name: 'Request void' }).click();
    await expect(w.getByText('Say why, so the approver and the buyer know')).toBeVisible();
    await w.getByLabel('Reason').fill('Wrong buyer on the invoice');
    await page.waitForTimeout(COOLDOWN_MS + 50); // a person takes longer than the double-press guard to type a reason
    await w.getByRole('button', { name: 'Request void' }).click();
    await expect(w.getByText('Void requested — a second person approves it.')).toBeVisible();
    await expect(w.getByText(/waits for a second person to approve it \(four-eyes\)/)).toBeVisible();
  });

  test('search and filters are server-side and the platform view is read-only', async ({ page }) => {
    const w = await org(page);
    await w.getByRole('tab', { name: 'Overdue' }).click();
    await expect(w.getByRole('list', { name: 'Invoices' }).locator('li')).toHaveCount(1);
    await expect(row(w, 'INV-2026-001')).toContainText('Overdue by');
    await w.getByRole('tab', { name: 'All' }).click();
    await w.getByLabel('Search invoices').fill('Kavango');
    await expect(w.getByRole('list', { name: 'Invoices' }).locator('li')).toHaveCount(1);

    await openWorkbench(page, ID, { tier: 'md', cells: ALL_CELLS }); // PLATFORM
    const admin = page.locator('[data-tier="md"]');
    await expect(admin.getByText('Every invoice on the platform (read-only).')).toBeVisible();
    await expect(admin.getByRole('tab', { name: 'Drafts' })).toHaveCount(0);
    await expect(admin.locator('[data-action]')).toHaveCount(0);
  });
});

test.describe(`${ID} · portal (BFF session)`, () => {
  test.skip(!LANES.includes('portal'), 'portal lane not selected');

  test('renders on its real screen for a real session', async ({ browser }) => {
    const stage = stageOf(ID);
    const s = await portalSession(browser, stage);
    const stubbed = await stubPlannedOps(s.page, stage, fixtures as Record<string, Partial<Record<string, unknown>>>, ALL_CELLS);
    if (stubbed.length) console.log(`stage-1 stubs (NEW ops from fixtures): ${stubbed.join(', ')}`);
    await openScreen(s, stage);
    await expect(s.page.getByText(/not registered yet/i), 'widget registered with core').toHaveCount(0);
    // the signed admin console: Billing › Invoices in the menu, the platform's read-only view
    await expect(s.page.getByRole('link', { name: 'Invoices' })).toBeVisible();
    await expect(s.page.getByText('Every invoice on the platform (read-only).')).toBeVisible();
    if (LIVE) {
      // nothing stubbed: core answered through BFF → gateway → core (a fresh platform may have no invoices yet)
      await expect(s.page.getByRole('list', { name: 'Invoices' }).locator('li').first()
        .or(s.page.getByText('No invoices here yet'))).toBeVisible();
      const probe = await s.page.evaluate(async () => {
        const me = await (await fetch('/bff/session', { credentials: 'same-origin' })).json() as { user?: { tenantId?: string } };
        const tenant = me.user?.tenantId ?? '';
        const r = await fetch(`/api/tenants/${tenant}/invoices/summary?direction=ALL`, { credentials: 'same-origin' });
        return { status: r.status, body: r.ok ? await r.json() as { currency?: string; direction?: string } : null };
      });
      expect(probe.status, 'live invoice summary through the BFF').toBe(200);
      expect(probe.body).toMatchObject({ currency: 'NAD', direction: 'ALL' });
    } else {
      await expect(s.page.getByRole('list', { name: 'Invoices' }).locator('li').first()).toBeVisible();
    }
    await expect(s.page.getByText(/couldn.t load|something went wrong/i)).toHaveCount(0);
    await expect(s.page.locator('[data-action]')).toHaveCount(0);
    await s.context.close();
  });
});
