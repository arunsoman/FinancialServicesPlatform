/**
 * widget-register — registers ONE widget with core's widget registry through the workbench,
 * under a real BFF session (hosted IdP sign-in; the session cookie is the only credential —
 * no bearer token, no direct core call: FAPI 2.0 / AGENTS §4). Core re-checks
 * ui.widget.register on PUT /v1/ui/widgets.
 *
 * Run via `bun run widgets:register-bff <id>` (tools/widget-agent.ts), which sets
 * FSP_WIDGET_ID and records the result in the widget's .widget-stage.json.
 * Needs the stack (idp, gateway, bff, core) + vite dev server.
 */
import { writeFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

const BASE = process.env.FSP_SPA_URL ?? 'http://localhost:5173';
const ID = process.env.FSP_WIDGET_ID ?? '';
const USER = process.env.FSP_WIDGET_REGISTER_USER ?? 'platform-admin';
const PASS = process.env.FSP_WIDGET_REGISTER_PASS ?? process.env.FSP_BOOTSTRAP_PASSWORD ?? 'fsp-admin';
const OUT = process.env.FSP_WIDGET_RESULT ?? '';

interface ServerWidget { id: string; version: string; status?: string }

test(`register widget ${ID} with core (BFF session)`, async ({ browser }) => {
  expect(ID, 'FSP_WIDGET_ID').toMatch(/^[a-z][a-z0-9]*\.[a-z][a-z0-9-]*$/);
  const ctx = await browser.newContext({ baseURL: BASE });
  const page = await ctx.newPage();
  await page.goto('/widget-workbench/registry');
  await page.getByRole('button', { name: 'Sign in' }).first().click();
  await page.waitForSelector('#u', { timeout: 30_000 }); // hosted IdP page (PAR + PKCE behind the BFF)
  await page.fill('#u', USER);
  await page.fill('#p', PASS);
  await page.click('button:has-text("Sign in")');
  await page.waitForURL((u) => u.pathname.startsWith('/widget-workbench'), { timeout: 30_000 });
  await page.goto('/widget-workbench/registry');
  await page.waitForLoadState('networkidle');

  const row = page.locator('tr', { has: page.locator('.mono', { hasText: new RegExp(`^${ID.replace('.', '\\.')}@`) }) });
  await expect(row, `widget ${ID} is in this build's registry (is it under widgets/<area>/<name>/ ?)`).toHaveCount(1);
  const version = (await row.locator('.mono').first().innerText()).split('@')[1]?.trim() ?? '';

  const button = row.getByRole('button', { name: /^Register / });
  if (await button.count()) {
    await expect(button, `${USER} needs ui.widget.register`).toBeEnabled();
    await button.click();
    await expect(row.getByText('registered', { exact: true })).toBeVisible({ timeout: 20_000 });
  }

  const list = await page.request.get(`${BASE}/api/ui/widgets`);
  expect(list.status(), 'GET /api/ui/widgets').toBe(200);
  const held = ((await list.json()) as ServerWidget[]).find((w) => w.id === ID);
  expect(held?.version, `core holds ${ID}@${version}`).toBe(version);
  if (OUT) writeFileSync(OUT, JSON.stringify({ id: ID, version, by: USER, at: new Date().toISOString() }));
  console.log(`registered ${ID}@${version} as ${USER}`);
});
