/**
 * Shared E2E plumbing for the onboarding chain + the gap probes.
 *
 * Every persona gets its OWN browser context (own cookies → own BFF session → own CSRF
 * token); four-eyes is maker ≠ checker at person_id, so sessions are never shared.
 * The only credential surface is the IdP's hosted sign-in page (#u / #p / "Sign in") —
 * reached through the real portal → BFF PAR → IdP chain (FAPI2: no password grant here).
 */
import { expect, type APIRequestContext, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { createHash, randomUUID } from 'node:crypto';

export const BOOT_PASS = process.env.FSP_BOOTSTRAP_PASSWORD ?? 'fsp-admin';
export const PERSON_PASS = process.env.FSP_PERSON_PASSWORD ?? 'fsp-person-2026';
export const PLATFORM_TENANT_ID = '00000000-0000-0000-0000-000000000001';
const MANIFEST_PATH = new URL('../../../backend/plugins/kyc/plugin-manifest.json', import.meta.url);

export interface Session {
  name: string;
  username: string;
  context: BrowserContext;
  page: Page;
  request: APIRequestContext;
  /** CSRF token of THIS session (double-submit) — never shared across personas. */
  csrf?: string;
}

export const BASE_URL = process.env.FSP_SPA_URL ?? 'http://localhost:5173';

export interface SignInOpts {
  /** Set a specific replacement password (else a generated one). */
  password?: string;
  /** Keep the forced-change page open (scenario asserts it manually). */
  leaveChangePending?: boolean;
}

/** Full browser login: portal /login → BFF PAR → hosted IdP page → back to the portal.
 *  F-8: persons born with the bootstrap password land on the IdP's forced change page
 *  first — by default the helper transparently completes it with a fresh password so
 *  scenario bodies stay linear; drive it manually via `leaveChangePending`. */
export async function signIn(browser: Browser, name: string, portalPath: string, username: string, password: string, opts: SignInOpts = {}): Promise<Session> {
  const context = await browser.newContext({ baseURL: BASE_URL });
  const page = await context.newPage();
  await page.goto(`${portalPath}/login`);
  await page.waitForSelector('#u', { timeout: 30_000 });
  await page.fill('#u', username);
  await page.fill('#p', password);
  await page.click('button:has-text("Sign in")');
  // F-8 branch: the IdP re-renders a change form (#np) instead of the code redirect.
  const changeForm = page.locator('#np');
  if (await changeForm.isVisible({ timeout: 5_000 }).catch(() => false)) {
    if (opts.leaveChangePending) {
      return { name, username, context, page, request: context.request, pendingChange: true };
    }
    await completePasswordChange(page, opts.password ?? `E2e-${suffix()}!pw`);
  }
  await page.waitForURL((u) => u.pathname.startsWith(portalPath), { timeout: 30_000 });
  await page.waitForLoadState('networkidle');
  return { name, username, context, page, request: context.request };
}

/** Complete the hosted IdP first-login change form (#np/#npc); sign-in then resumes. */
export async function completePasswordChange(page: Page, newPassword: string, confirm = newPassword): Promise<void> {
  await page.fill('#np', newPassword);
  await page.fill('#npc', confirm);
  await page.click('button:has-text("Set new password")');
}

export interface SessionUser { userId: string; username: string; tenantId: string; tenantType: string }

/** BFF session probe — `{authenticated, user}` (BffDtos.SessionResponse). */
export async function sessionUser(s: Session): Promise<SessionUser> {
  const r = await s.request.get(`${BASE_URL}/bff/session`);
  expect(r.status(), `${s.name}: BFF session endpoint`).toBe(200);
  const body = (await r.json()) as { authenticated: boolean; user: SessionUser | null };
  expect(body.authenticated, `${s.name}: session must be authenticated`).toBe(true);
  return body.user!;
}

async function csrf(s: Session): Promise<string> {
  if (!s.csrf) {
    const r = await s.request.get(`${BASE_URL}/bff/csrf`);
    s.csrf = ((await r.json()) as { token: string }).token;
  }
  return s.csrf;
}

export type Method = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';

/** Authenticated BFF call (/api/** → gateway /v1/**). Mutations carry CSRF + Idempotency-Key. */
export async function api<T = unknown>(s: Session, method: Method, path: string, body?: unknown): Promise<{ status: number; json: T }> {
  const headers: Record<string, string> = {};
  if (method !== 'GET') {
    headers['X-XSRF-TOKEN'] = await csrf(s);
    headers['Idempotency-Key'] = randomUUID(); // AGENTS §2e — every mutation
  }
  const r = await s.request.fetch(`${BASE_URL}/api${path}`, { method, headers, data: body });
  return { status: r.status(), json: (await r.json().catch(() => ({}))) as T };
}

export interface Approval { id: string; operationType: string; status: string; targetResourceId: string }

export async function pendingApproval(s: Session, tenantId: string, operationType: string): Promise<Approval | undefined> {
  const { status, json } = await api<Approval[]>(s, 'GET', `/approvals?tenantId=${tenantId}&status=PENDING_CHECKER`);
  expect(status, `${s.name}: approval queue readable`).toBe(200);
  return json.find((a) => a.operationType === operationType && a.status === 'PENDING_CHECKER');
}

/** API-lane checker half (used for SETUP only; the probes drive the UI). */
export async function approveVia(s: Session, tenantId: string, operationType: string, label: string): Promise<string> {
  const row = await pendingApproval(s, tenantId, operationType);
  expect(row, `a PENDING ${operationType} approval must exist (${label})`).toBeDefined();
  const { status, json } = await api(s, 'POST', `/approvals/${row!.id}/approve`, { reason: `e2e: ${label}` });
  expect(status, `${operationType} approved by ${s.name}: ${JSON.stringify(json)}`).toBe(200);
  return row!.id;
}

/** Poll with a real delay between tries. */
export async function pollUntil(fn: () => Promise<boolean>, label: string, tries = 40, delayMs = 500): Promise<void> {
  for (let i = 0; i < tries; i++) {
    if (await fn()) return;
    await new Promise((r) => setTimeout(r, delayMs));
  }
  throw new Error(`pollUntil: ${label} never became true`);
}

/** Clear any leftover act-as session for this person (one-live-session-per-person; a failed
 *  previous run can leave it live for 30 idle minutes) — deterministic, no hydration race. */
export async function clearActAs(s: Session): Promise<void> {
  const r = await s.request.delete(`${BASE_URL}/bff/act-as`, { headers: { 'X-XSRF-TOKEN': await csrf(s) } });
  void r; // 204 when exited; 401/403 when there was nothing to exit — either is fine
}

export const suffix = (): string => randomUUID().slice(0, 8);

/** Platform governance setup: KYC plugin REGISTERED (four-eyes) — idempotent across runs. */
export async function ensureKycPlugin(maker: Session, checker: Session): Promise<void> {
  const existing = await api<{ status?: string }>(maker, 'GET', '/platform/plugins/kyc/1.1.0');
  if (existing.status === 200 && existing.json.status === 'REGISTERED') return;
  if (existing.status !== 200) {
    const manifest = readFileSync(MANIFEST_PATH, 'utf-8');
    const manifestHash = createHash('sha256').update(manifest).digest('hex');
    const reg = await api(maker, 'POST', '/platform/plugins', {
      code: 'kyc', version: '1.1.0', baseUrl: 'http://localhost:8083', manifest, manifestHash,
    });
    expect(reg.status, `plugin registration opened: ${JSON.stringify(reg.json)}`).toBe(200);
  }
  await approveVia(checker, PLATFORM_TENANT_ID, 'PLUGIN_REGISTER', 'kyc plugin registration');
}

export interface PspFixture { tenantId: string; adminUsername: string; checkerUsername: string }

/** Platform creates a PSP (manufacturer) and installs the KYC plugin on it (four-eyes). */
export async function createManufacturer(maker: Session, checker: Session): Promise<PspFixture> {
  const s = suffix();
  const adminUsername = `acme.admin.${s}`;
  const checkerUsername = `acme.checker.${s}`;
  const created = await api<{ tenantId: string }>(maker, 'POST', '/tenants', {
    legalName: `Acme Manufacturers ${s} (Pty) Ltd`,
    adminUsername, adminFullName: 'Acme Administrator',
    checkerUsername, checkerFullName: 'Acme Checker',
  });
  expect(created.status, `PSP created: ${JSON.stringify(created.json)}`).toBe(200);
  const tenantId = created.json.tenantId;
  const install = await api(maker, 'POST', `/tenants/${tenantId}/plugins`, { code: 'kyc', version: '1.1.0', config: null });
  expect(install.status, `plugin install opened: ${JSON.stringify(install.json)}`).toBe(200);
  await approveVia(checker, tenantId, 'PLUGIN_INSTALL', 'kyc plugin install');
  return { tenantId, adminUsername, checkerUsername };
}

/** A complete, valid KYC application (NamibiaRules-clean; money = minor-unit STRINGS). */
export function kycApplication(subType: 'DISTRIBUTOR' | 'WHOLESALER' | 'MERCHANT', legalName: string): Record<string, unknown> {
  const digits = () => randomUUID().replace(/\D/g, '');
  return {
    subType,
    legalName,
    entityType: 'PRIVATE_LIMITED',
    registrationNumber: `${100000 + Math.floor(Math.random() * 300000)}`,
    incorporationDate: '2018-06-01',
    taxId: `${10000000 + Math.floor(Math.random() * 90000000)}`,
    mccCode: '5411',
    expectedMonthlyVolume: '50000000', avgTicketSize: '250000',
    registeredAddress: `St ${suffix()}, Windhoek`,
    registeredCity: 'Windhoek', registeredRegion: 'Khomas',
    registeredPostal: '10001', registeredCountry: 'NAD',
    operationalAddressSame: true,
    ubos: [{
      fullName: 'Elsi Hove', dob: '1980-02-14', nationality: 'NAM',
      residentialAddress: '12 Fidel Castro St, Windhoek',
      phone: '+264-81-000-0000', email: 'elsi@example.na',
      idDocType: 'ID', idNumber: '80021400123', idExpiry: '2030-01-01',
      ownershipPct: 100, isPep: false,
    }],
    bankAccounts: [{
      holderName: legalName, bankName: 'BANK_WINDHOEK',
      branchCode: '481972', accountNumber: `1${digits().slice(0, 8)}`,
      accountType: 'CURRENT',
    }],
    // V36: the org's people activate by link/code on their primary contact (no shared password)
    admin: { fullName: 'Ada Admin', email: `admin.${suffix()}@example.na`, phone: null, primaryContact: 'EMAIL' },
    checker: { fullName: 'Carl Checker', email: `checker.${suffix()}@example.na`, phone: null, primaryContact: 'EMAIL' },
  };
}

/** Text the SPA shows for a menu point whose widget id is not in the registry. */
export const UNREGISTERED_WIDGET = /not registered yet/i;
/** Text of the portal eligibility wall. */
export const NO_ACCESS_WALL = 'No access to this portal';

/**
 * File a KYC application through the REAL filing form — the kyc.manage “New application”
 * drawer: six steps (Company · Business · Addresses · Owners · Bank accounts · Sign-in), validation
 * on Next/Submit, selects fed by the plugin's master values. Equivalent data to kycApplication.
 * Returns the created case id, read off the drawer's ?case= deep link.
 */
export async function fileKycUi(
  session: Session, portal: 'manufacturer' | 'distributor' | 'wholesaler',
  subType: 'DISTRIBUTOR' | 'WHOLESALER' | 'MERCHANT', legalName: string,
): Promise<string> {
  const page = session.page;
  const regNo = `${100000 + Math.floor(Math.random() * 300000)}`;
  const taxId = `${10000000 + Math.floor(Math.random() * 90000000)}`;
  const accountNo = `1${String(Math.floor(Math.random() * 10_000_000)).padStart(8, '0')}`;
  await page.goto(`/${portal}/onboarding`);
  await page.getByRole('button', { name: 'New application' }).click();

  // Company — the master values (entity type) load async with the drawer; wait for options.
  const entityType = page.getByLabel('Entity type');
  await expect(entityType.locator('option')).not.toHaveCount(0, { timeout: 30_000 });
  // the ladder fixes the type to the filer's next level (core TenantLadder) — assert, don't choose
  const orgType = page.getByLabel('Organization type');
  if (await orgType.isDisabled()) {
    await expect(orgType).toHaveValue(subType);
  } else {
    await orgType.selectOption({ value: subType });
  }
  await entityType.selectOption({ label: 'Private Limited / LLC' });
  await page.getByLabel('Registered name').fill(legalName);
  await page.getByLabel('Business Registration no.').fill(regNo);
  await page.getByLabel('Tax ID (TIN)').fill(taxId);
  await page.getByLabel('Incorporation date').fill('2018-06-01');

  // Business — volume/avg ticket are major units in the form (the widget converts to minor).
  await page.getByRole('button', { name: 'Next ›' }).click();
  await page.getByLabel('Merchant category').selectOption({ label: 'Grocery Stores' });
  await page.getByLabel('Expected monthly volume (NAD)').fill('500000');
  await page.getByLabel('Average ticket (NAD)').fill('2500');

  // Addresses — operating address defaults to “same as registered”.
  await page.getByRole('button', { name: 'Next ›' }).click();
  await page.getByLabel('Street address').fill(`St ${suffix()}, Windhoek`);
  await page.getByLabel('Town / city').fill('Windhoek');
  await page.getByLabel('Region').selectOption({ label: 'Khomas' });
  // postal code is optional — left empty

  // Owners — one UBO at 100 %.
  await page.getByRole('button', { name: 'Next ›' }).click();
  const owner = page.locator('fieldset', { hasText: 'Owner 1' });
  await owner.getByLabel('Full name').fill('Elsi Hove');
  await owner.getByLabel('ID document').selectOption({ label: 'National ID' });
  await owner.getByLabel('ID number').fill('80021400123');
  await owner.getByLabel('Date of birth').fill('1980-02-14');
  await owner.getByLabel('Nationality').selectOption({ label: 'Namibia' });
  await owner.getByLabel('Ownership %').fill('100');

  // Bank accounts — one settlement account.
  await page.getByRole('button', { name: 'Next ›' }).click();
  const bank = page.locator('fieldset', { hasText: 'Account 1' });
  await bank.getByLabel('Account holder').fill(legalName);
  await bank.getByLabel('Bank').selectOption({ label: 'Bank Windhoek' });
  await bank.getByLabel('Branch code').fill('481972');
  await bank.getByLabel('Account number').fill(accountNo);
  await bank.getByLabel('Account type').selectOption({ label: 'Current / cheque' });

  // Sign-in — the administrator and checker each get an activation on their primary contact.
  await page.getByRole('button', { name: 'Next ›' }).click();
  for (const [who, name] of [['Administrator', 'Ada Admin'], ['Checker', 'Carl Checker']] as const) {
    const section = page.locator('section', { hasText: who }).first();
    await section.getByLabel('Full name').fill(name);
    await section.getByLabel('Email address').fill(`${who.toLowerCase()}.${suffix()}@example.na`);
  }

  await page.getByRole('button', { name: 'Submit application' }).click();
  await expect(page.getByText(`${legalName} was submitted for review as `)).toBeVisible({ timeout: 10_000 });
  const url = new URL(page.url());
  expect(url.searchParams.get('case'), 'drawer deep-links to the created case').toMatch(/^[0-9a-f-]+$/);
  return url.searchParams.get('case')!;
}

/**
 * Approve a pending KYC case through the REAL decision panel (kyc.manage case drawer):
 * Approve → confirm in the alertdialog → wait for the decided state.
 */
export async function approveKycUi(session: Session, portal: 'manufacturer' | 'distributor' | 'wholesaler', caseId: string): Promise<void> {
  const page = session.page;
  // /kyc/reviews is the checker's screen (kyc.approve gate) — /onboarding is maker-only.
  await page.goto(`/${portal}/kyc/reviews?case=${caseId}`);
  await expect(page.getByText(NO_ACCESS_WALL)).toHaveCount(0, { timeout: 10_000 });
  await page.getByRole('button', { name: 'Approve', exact: true }).click();
  const confirm = page.getByRole('alertdialog', { name: 'Approve this application?' });
  await confirm.getByRole('button', { name: 'Approve', exact: true }).click();
  await expect(page.getByText('approved — the organization is now active')).toBeVisible({ timeout: 10_000 });
}

/**
 * The applicant admin username the KYC saga provisions — mirrors
 * KycOnboardingSagaService.adminUsername (slug of the legal name, ≤40 chars, + case-number tail).
 */
export function sagaAdminUsername(legalName: string, caseNo: string): string {
  let slug = legalName.toLowerCase().replace(/[^a-z0-9]+/g, '.').replace(/^\.+|\.+$/g, '');
  if (slug.length > 40) slug = slug.slice(0, 40).replace(/\.+$/, '');
  if (slug.length < 3) slug = 'org';
  return `${slug}.${caseNo.slice(caseNo.lastIndexOf('-') + 1)}`;
}
