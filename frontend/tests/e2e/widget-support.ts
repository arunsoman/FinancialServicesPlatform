/**
 * widget-support.ts — shared Playwright helpers for per-widget end-to-end specs
 * (tests/e2e/widgets/<id>.spec.ts, scaffolded by `bun run widgets:e2e <id> --scaffold`).
 *
 * Two lanes, one spec:
 *   - WORKBENCH (stage 1+): /widget-workbench/w/<id> signed out → every source answers from the
 *     widget's fixtures. Asserts the frame state per scenario at every grid tier and the layout
 *     geometry (no horizontal overflow, nothing outside the cell) via the page's window.__wb hook.
 *   - PORTAL (stage 1 with stack up; mandatory in stage 2): a real BFF sign-in (hosted IdP →
 *     PAR → session cookie — no token ever reaches the browser, FAPI 2.0) and the real screen.
 *     While the widget is in stage 1 the operations still marked NEW in .widget-stage.json are
 *     answered from fixtures by page.route; with FSP_WIDGET_LIVE=1 (stage 2) nothing is
 *     stubbed and the whole chain gateway → core runs.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, type Browser, type Page } from '@playwright/test';
import { BOOT_PASS, signIn, type Session } from './support';

export const TIERS = ['xs', 'sm', 'md', 'lg'] as const;
export type Tier = (typeof TIERS)[number];
export type Scenario = 'default' | 'empty' | 'error' | 'not-ready';

/** frame state the workbench shows for each fixture scenario (WidgetFrame.stateOf) */
export const EXPECTED_STATE: Record<Scenario, string> = { default: 'ready', empty: 'empty', error: 'error', 'not-ready': 'error' };

export const LIVE = process.env.FSP_WIDGET_LIVE === '1';

interface Geometry { tier: string; cellWidth: number; overflowX: string[]; outsideCell: string[]; smallTargets: string[] }

export interface StageOp { name: string; kind: 'source' | 'command'; method: string; path: string; status: 'REUSE' | 'NEW' }
export interface StageFile {
  id: string;
  stage: string;
  ops: StageOp[];
  /** where the portal lane finds the widget — the agent sets it once the screen is wired */
  screen?: { portalPath?: string; screenPath?: string; user?: string };
}

const WIDGETS = join(process.cwd(), 'src', 'portals', 'shared', 'widgets');

export function stageOf(id: string): StageFile {
  const [area, name] = id.split('.');
  return JSON.parse(readFileSync(join(WIDGETS, area, name, '.widget-stage.json'), 'utf8')) as StageFile;
}

/** Opens the widget page for one setup and waits until no frame is loading. */
export async function openWorkbench(
  page: Page, id: string,
  opts: { scenario?: Scenario; cells?: 'all' | 'none' | string[]; tier?: Tier; portal?: string; config?: Record<string, string> } = {},
): Promise<void> {
  const q = new URLSearchParams();
  if (opts.scenario) q.set('scenario', opts.scenario);
  if (opts.cells) q.set('cells', Array.isArray(opts.cells) ? opts.cells.join(',') || 'none' : opts.cells);
  if (opts.tier) { q.set('single', '1'); q.set('tier', opts.tier); }
  if (opts.portal) q.set('portal', opts.portal);
  for (const [k, v] of Object.entries(opts.config ?? {})) q.set(`cfg_${k}`, v);
  await page.goto(`/widget-workbench/w/${id}?${q.toString()}`);
  await expect(page.locator('[data-frame-state]').first()).toBeVisible();
  await expect.poll(async () => page.locator('[data-frame-state]').allInnerTexts(), { message: 'frames settle' })
    .not.toContain('loading');
}

/** Every visible tier frame shows `state` (hidden tiers are allowed) and has clean geometry. */
export async function expectFrames(page: Page, state: string, opts: { geometry?: boolean } = {}): Promise<void> {
  for (const t of TIERS) {
    const frame = page.locator(`[data-tier="${t}"]`);
    if (!(await frame.count())) continue;
    const shown = (await frame.locator('[data-frame-state]').innerText()).trim();
    if (shown === 'hidden') continue;
    expect(shown, `tier ${t}: frame state`).toBe(state);
    if (opts.geometry === false) continue;
    const g = await page.evaluate((tier) => (window as unknown as { __wb?: { measure: (x: string) => Geometry | null } }).__wb?.measure(tier) ?? null, t);
    expect(g, `tier ${t}: measurable`).not.toBeNull();
    expect(g!.overflowX, `tier ${t}: horizontal overflow (ui-std: the page never scrolls sideways)`).toEqual([]);
    expect(g!.outsideCell, `tier ${t}: content escapes its grid cell`).toEqual([]);
  }
}

/** Fixture scenario sweep: each scenario renders its expected state at every tier. */
export async function sweepScenarios(page: Page, id: string, scenarios: Scenario[] = ['default', 'empty', 'error']): Promise<void> {
  for (const s of scenarios) {
    await openWorkbench(page, id, { scenario: s });
    await expectFrames(page, EXPECTED_STATE[s], { geometry: s === 'default' });
  }
}

/** Without any cell the widget is forbidden and no gated action is reachable. */
export async function expectGatedWithoutCells(page: Page, id: string, actionIds: string[]): Promise<void> {
  await openWorkbench(page, id, { cells: 'none' });
  await expectFrames(page, 'forbidden', { geometry: false });
  for (const a of actionIds) await expect(page.locator(`[data-action="${a}"]`), `action ${a} without its cell`).toHaveCount(0);
}

/**
 * With exactly `cells`, each listed action is visible in the md frame in at least one of
 * `scenarios` (many actions are conditional — e.g. "Provision" only on an empty book).
 */
export async function expectActionsWith(
  page: Page, id: string, cells: string[], actionIds: string[], scenarios: Scenario[] = ['default', 'empty'],
): Promise<void> {
  const missing = new Set(actionIds);
  for (const scenario of scenarios) {
    if (!missing.size) break;
    await openWorkbench(page, id, { cells, tier: 'md', scenario });
    for (const a of [...missing]) {
      if (await page.locator(`[data-tier="md"] [data-action="${a}"]`).first().isVisible()) missing.delete(a);
    }
  }
  expect([...missing], `actions never visible with ${cells.join(',')} in scenarios ${scenarios.join('/')}`).toEqual([]);
}

/* ------------------------------------------------------------------ portal lane */

/** A REAL BFF session (hosted IdP sign-in) — the only credential path (FAPI 2.0, AGENTS §4). */
export async function portalSession(browser: Browser, stage: StageFile): Promise<Session> {
  const portal = stage.screen?.portalPath ?? '/admin';
  const user = process.env.FSP_WIDGET_E2E_USER ?? stage.screen?.user ?? 'platform-admin';
  const pass = process.env.FSP_WIDGET_E2E_PASS ?? BOOT_PASS;
  return signIn(browser, `widget e2e (${user})`, portal, user, pass);
}

/** `/api/tenants/{tenantId}/invoices/{invoiceId}` → a regex with one named group per `{param}`. */
function pathMatcher(apiPath: string): RegExp {
  const parts = apiPath.split(/\{(\w+)\}/);
  const src = parts.map((p, i) => (i % 2 ? `(?<${p}>[^/]+)` : p.replace(/[.*+?^$()|[\]\\]/g, '\\$&'))).join('');
  return new RegExp(`^${src}$`);
}

/**
 * Stage 1: answer every operation still marked NEW from the widget's `default` fixture so the
 * real screen renders before the backend exists. A resolver fixture PLAYS the server, exactly as
 * in the workbench: it gets the path + query params, the body and `permissions`, and an error it
 * throws (ApiError) becomes that HTTP status. When several NEW paths match a URL the most literal
 * wins (`/invoices/summary` before `/invoices/{invoiceId}`). A no-op when FSP_WIDGET_LIVE=1.
 */
export async function stubPlannedOps(
  page: Page, stage: StageFile, fixtures: Record<string, Partial<Record<string, unknown>>>, permissions: readonly string[] = [],
): Promise<string[]> {
  if (LIVE) return [];
  const ops = stage.ops.filter((o) => o.status === 'NEW').map((o) => ({ ...o, rx: pathMatcher(o.path), params: (o.path.match(/\{/g) ?? []).length }));
  if (!ops.length) return [];
  await page.route((url) => ops.some((o) => o.rx.test(url.pathname)), async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const op = ops.filter((o) => o.method === req.method() && o.rx.test(url.pathname)).sort((a, b) => a.params - b.params)[0];
    if (!op) return route.fallback();
    const answer = fixtures[op.name]?.['default'];
    try {
      const params = { ...Object.fromEntries(url.searchParams), ...(op.rx.exec(url.pathname)?.groups ?? {}) };
      const body = req.postData() ? JSON.parse(req.postData() as string) : {};
      const value = typeof answer === 'function' ? answer({ params, body, permissions, scenario: 'default' }) : answer;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(value ?? {}) });
    } catch (e) {
      const err = e as { status?: number; code?: string; message?: string; detail?: string };
      await route.fulfill({
        status: err.status ?? 500, contentType: 'application/problem+json',
        body: JSON.stringify({ code: err.code ?? 'FIXTURE_ERROR', title: err.code ?? 'Error', detail: err.detail ?? err.message ?? 'fixture error' }),
      });
    }
  });
  return ops.map((o) => `${o.method} ${o.path}`);
}

/** Opens the portal route that mounts the widget (stage file screen.screenPath). */
export async function openScreen(s: Session, stage: StageFile): Promise<void> {
  const path = stage.screen?.screenPath;
  expect(path, '.widget-stage.json screen.screenPath — set it to the portal route that mounts this widget').toBeTruthy();
  await s.page.goto(path!);
  await s.page.waitForLoadState('networkidle');
}
