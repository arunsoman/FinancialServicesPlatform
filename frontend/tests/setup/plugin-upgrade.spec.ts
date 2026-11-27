/**
 * plugin-upgrade — dev setup: register a new version of a plugin type the PROPER way
 * (four-eyes), so the gateway routes its new operations.
 *   1. maker (platform-admin) submits POST /api/platform/plugins with the manifest + its sha256;
 *   2. checker (admin-checker, a different person) approves the PLUGIN_REGISTER approval;
 *   3. core marks it REGISTERED and supersedes the older registered version (one per code);
 *      existing installations are per plugin code and keep running on the new manifest.
 * Idempotent: an already-registered version is left alone.
 *
 * Then installs it on the platform root (inherited by every org below) if it isn't installed yet.
 * Run: `make plugin-upgrade` (PLUGIN=kyc by default; reads backend/plugins/<code>/plugin-manifest.json).
 */
import { expect, test, type APIRequestContext, type Browser } from '@playwright/test';
import { createHash, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';

const BASE = process.env.FSP_SPA_URL ?? 'http://localhost:5173';
const PASS = process.env.FSP_BOOTSTRAP_PASSWORD ?? 'fsp-admin';
const PLUGIN = process.env.PLUGIN ?? 'kyc';
const PLATFORM = '00000000-0000-0000-0000-000000000001';
/** Where each plugin process listens in dev (docs/runbook-fsp-local.md); PLUGIN_BASE_URL overrides. */
const DEV_BASE_URL: Record<string, string> = { kyc: 'http://localhost:8083', inventory: 'http://localhost:8084' };

// the install needs the registration: run in order, stop at the first failure
test.describe.configure({ mode: 'serial' });

interface Who { request: APIRequestContext; csrf: string }

/** Hosted IdP sign-in through the real portal → BFF PAR chain (FAPI2: no password grant). */
async function session(browser: Browser, username: string): Promise<Who> {
  const ctx = await browser.newContext({ baseURL: BASE });
  const page = await ctx.newPage();
  await page.goto('/widget-workbench/registry');
  await page.getByRole('button', { name: 'Sign in' }).first().click();
  await page.waitForSelector('#u', { timeout: 30_000 });
  await page.fill('#u', username);
  await page.fill('#p', PASS);
  await page.click('button:has-text("Sign in")');
  await page.waitForURL((u) => u.pathname.startsWith('/widget-workbench'), { timeout: 30_000 });
  const csrf = ((await (await ctx.request.get(`${BASE}/bff/csrf`)).json()) as { token: string }).token;
  return { request: ctx.request, csrf };
}

async function call<T>(who: Who, method: 'GET' | 'POST', path: string, data?: unknown): Promise<{ status: number; json: T }> {
  const headers: Record<string, string> = method === 'GET' ? {} : { 'X-XSRF-TOKEN': who.csrf, 'Idempotency-Key': randomUUID(), Origin: new URL(BASE).origin };
  const r = await who.request.fetch(`${BASE}/api${path}`, { method, headers, data });
  return { status: r.status(), json: (await r.json().catch(() => ({}))) as T };
}

test(`register the "${PLUGIN}" plugin's manifest version (four-eyes)`, async ({ browser }) => {
  const manifest = readFileSync(new URL(`../../../backend/plugins/${PLUGIN}/plugin-manifest.json`, import.meta.url), 'utf-8');
  const parsed = JSON.parse(manifest) as { version: string; baseUrl?: string };
  const version = parsed.version;
  const manifestHash = createHash('sha256').update(manifest).digest('hex');

  const maker = await session(browser, 'platform-admin');
  const existing = await call<{ id?: string; status?: string; baseUrl?: string }>(maker, 'GET', `/platform/plugins/${PLUGIN}/${version}`);
  if (existing.status === 200 && existing.json.status === 'REGISTERED') {
    console.log(`${PLUGIN} ${version} is already REGISTERED — nothing to do`);
    return;
  }
  let typeId = existing.json.id;
  if (existing.status !== 200) {
    // same base URL as the registered version the gateway routes to today
    const all = await call<{ items?: { code: string; status: string; baseUrl: string }[] } | { code: string; status: string; baseUrl: string }[]>(maker, 'GET', '/platform/plugins');
    const rows = Array.isArray(all.json) ? all.json : all.json.items ?? [];
    const baseUrl = process.env.PLUGIN_BASE_URL
      ?? rows.find((r) => r.code === PLUGIN && r.status === 'REGISTERED')?.baseUrl ?? parsed.baseUrl ?? DEV_BASE_URL[PLUGIN];
    expect(baseUrl, `a base URL for ${PLUGIN}`).toBeTruthy();
    const reg = await call<{ id: string }>(maker, 'POST', '/platform/plugins', { code: PLUGIN, version, baseUrl, manifest, manifestHash });
    expect(reg.status, `registration opened: ${JSON.stringify(reg.json)}`).toBe(200);
    typeId = reg.json.id;
    console.log(`${PLUGIN} ${version}: registration submitted by platform-admin`);
  }

  const checker = await session(browser, 'admin-checker');
  const queue = await call<{ id: string; operationType: string; status: string; targetResourceId: string }[]>(
    checker, 'GET', `/approvals?tenantId=${PLATFORM}&status=PENDING_CHECKER`);
  expect(queue.status).toBe(200);
  // THIS registration's approval — never "any pending PLUGIN_REGISTER"
  const row = queue.json.find((a) => a.operationType === 'PLUGIN_REGISTER' && a.targetResourceId === typeId);
  expect(row, `the pending PLUGIN_REGISTER approval of ${PLUGIN} ${version}`).toBeDefined();
  const ok = await call(checker, 'POST', `/approvals/${row!.id}/approve`, { reason: `register ${PLUGIN} ${version}` });
  expect(ok.status, `approved by admin-checker: ${JSON.stringify(ok.json)}`).toBe(200);

  const after = await call<{ status: string }>(maker, 'GET', `/platform/plugins/${PLUGIN}/${version}`);
  expect(after.json.status).toBe('REGISTERED');
  console.log(`${PLUGIN} ${version} REGISTERED (four-eyes: platform-admin → admin-checker)`);
});

test(`install the "${PLUGIN}" plugin on the platform root — inherited by every org below (four-eyes)`, async ({ browser }) => {
  const maker = await session(browser, 'platform-admin');
  type Inst = { id: string; pluginCode: string; status: string };
  const listed = await call<Inst[] | { items?: Inst[] }>(maker, 'GET', `/tenants/${PLATFORM}/plugins`);
  const rows = Array.isArray(listed.json) ? listed.json : listed.json.items ?? [];
  const current = rows.find((r) => r.pluginCode === PLUGIN && r.status !== 'DEACTIVATED');
  if (current) {
    console.log(`${PLUGIN} is already installed on the platform root (${current.status}) — nothing to do`);
    return;
  }
  const manifest = JSON.parse(readFileSync(new URL(`../../../backend/plugins/${PLUGIN}/plugin-manifest.json`, import.meta.url), 'utf-8')) as { version: string };
  const inst = await call<{ id: string }>(maker, 'POST', `/tenants/${PLATFORM}/plugins`, { code: PLUGIN, version: manifest.version, config: null });
  expect(inst.status, `install opened: ${JSON.stringify(inst.json)}`).toBe(200);

  const checker = await session(browser, 'admin-checker');
  const queue = await call<{ id: string; operationType: string; targetResourceId: string }[]>(
    checker, 'GET', `/approvals?tenantId=${PLATFORM}&status=PENDING_CHECKER`);
  const row = queue.json.find((a) => a.operationType === 'PLUGIN_INSTALL' && a.targetResourceId === inst.json.id);
  expect(row, `the pending PLUGIN_INSTALL approval of ${PLUGIN}`).toBeDefined();
  const ok = await call(checker, 'POST', `/approvals/${row!.id}/approve`, { reason: `install ${PLUGIN} on the platform root` });
  expect(ok.status, `approved by admin-checker: ${JSON.stringify(ok.json)}`).toBe(200);
  console.log(`${PLUGIN} installed on the platform root (four-eyes: platform-admin → admin-checker); it reports readiness itself`);
});
