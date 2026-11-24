/**
 * e2e/onboarding-chain.spec.ts — the complete supply-chain onboarding story
 * (docs/onboarding-script-steps.md), driven through the REAL portals (Vite :5173), the BFF
 * (:8080) and the hosted IdP sign-in page. NO database access: the GAP-1 SQL shim is gone —
 * fresh organizations are born with the rights the story needs (RoleTemplates + V19).
 *
 *   Platform ──creates──▶ Manufacturer (PSP) ──onboards──▶ Distributor
 *   ──onboards──▶ Merchant (+ named checker user, four-eyes) ──posts──▶ first JE
 *
 * [UI]  = clicked in a portal screen     [API] = same signed-in session via /api/**
 * Remaining [API] legs: the KYC application form (a long multi-tab form — its screen is
 * asserted, the filing itself goes through the plugin API) and the journal entry legs.
 * Plugin install approval = [UI] through act-as (the PSP's own queue; the platform checker
 * steps in rather than deciding outside the tenant).
 *
 * Prereqs: the dev stack (`make infra-up tb-start backend-idp backend-core backend-gateway
 * backend-bff backend-plugin-kyc frontend-dev`); core signs the seeded portal menus at boot.
 */
import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import {
  api, approveKycUi, approveVia, BOOT_PASS, clearActAs, completePasswordChange, ensureKycPlugin, fileKycUi, NO_ACCESS_WALL, PERSON_PASS,
  PLATFORM_TENANT_ID, pendingApproval, pollUntil, sagaAdminUsername, sessionUser, signIn, suffix, type Session,
} from './support';

test.describe.configure({ mode: 'serial' });

/** [UI] the checker opens Approvals and approves one request by clicking. */
async function approveInUi(checker: Session, portal: string, approvalId: string, reason: string) {
  const page = checker.page;
  await page.goto(`${portal}/approvals`);
  const card = page.locator(`[data-approval-id="${approvalId}"]`);
  await expect(card).toBeVisible();
  await card.getByRole('button', { name: 'Approve' }).click();
  await page.getByLabel('Reason').fill(reason);
  await page.getByRole('button', { name: 'Confirm approval' }).click();
  await expect(card.getByText('Approved', { exact: true })).toBeVisible();
}

test('manufacturer → distributor → merchant onboarding chain', async ({ browser }) => {
  test.setTimeout(420_000);

  /* ── SCENE 1 · platform maker + checker, separate contexts (four-eyes at person_id) ── */
  const maker = await signIn(browser, 'platform maker', '/admin', 'platform-admin', BOOT_PASS);
  const checker = await signIn(browser, 'platform checker', '/admin', 'admin-checker', BOOT_PASS);
  expect((await sessionUser(maker)).username).toBe('platform-admin');
  expect((await sessionUser(checker)).username).toBe('admin-checker');
  await expect(maker.page.getByRole('navigation', { name: 'Portal navigation' })).toBeVisible(); // GAP-7 closed

  /* ── SCENE 2 · KYC plugin registered (four-eyes), manufacturer created, plugin installed ── */
  await ensureKycPlugin(maker, checker);

  // [UI] platform Organization screen (onboard.list: directory + New organization)
  const s = suffix();
  const mfrAdminUsername = `acme.admin.${s}`;
  const mfrCheckerUsername = `acme.checker.${s}`;
  const legalName = `Acme Manufacturers ${s} (Pty) Ltd`;
  await maker.page.goto('/admin/organizations');
  await maker.page.getByRole('button', { name: 'New organization' }).click();
  await maker.page.getByLabel('Legal name').fill(legalName);
  await maker.page.getByLabel('Administrator username').fill(mfrAdminUsername);
  await maker.page.getByLabel('Administrator name').fill('Acme Administrator');
  await maker.page.getByLabel('Checker username').fill(mfrCheckerUsername);
  await maker.page.getByLabel('Checker name').fill('Acme Checker');
  await maker.page.getByRole('button', { name: 'Create organization' }).click();
  await expect(maker.page.getByText(mfrAdminUsername)).toBeVisible();
  const { json: psps } = await api<{ id: string; legalName: string }[]>(maker, 'GET', `/tenants/${PLATFORM_TENANT_ID}/children`);
  const mfrTenantId = psps.find((t) => t.legalName === legalName)!.id;

  // [UI] plugin installation request (plugins.governance) → [UI · act-as] checker approves
  await maker.page.goto(`/admin/plugins?org=${mfrTenantId}`);
  await maker.page.getByLabel('Install plugin').selectOption({ label: 'kyc 1.0.0' });
  await maker.page.getByRole('button', { name: 'Request installation' }).click();
  await expect(maker.page.getByText(/Installation of kyc requested/)).toBeVisible();

  /* The approval lives on the PSP's queue — the PSP consents to a machine client entering its
   * perimeter (customer-consent model, V20), so the platform does NOT decide from outside:
   * the checker STEPS IN via act-as (plans/act-as-tenant-plan.md) and clicks, like it will
   * for any other stuck tenant. Audit names the real person; the ceiling caps them at the
   * PSP's own role keys. */
  // [UI] the checker finds the PSP row (onboard.psp) and starts acting as it
  await clearActAs(checker); // a failed previous run may have left the session acting (by design)
  await checker.page.goto('/admin/organizations');
  const pspRow = checker.page.locator('li', { hasText: legalName });
  await expect(pspRow).toBeVisible();
  await pspRow.getByRole('button', { name: `Act as ${legalName}` }).click();
  // [UI] the banner names the person on record and the target tenant
  await expect(checker.page.getByTestId('act-as-banner')).toContainText(legalName);
  const install = await pendingApproval(checker, mfrTenantId, 'PLUGIN_INSTALL');
  expect(install, 'PLUGIN_INSTALL approval is on the PSP queue (visible to the acting person)').toBeDefined();
  // [UI] the checker approves in the (PSP) queue — real clicks, four-eyes on person_ids
  await approveInUi(checker, '/admin', install!.id, `e2e: kyc plugin install (act-as ${s})`);
  // [UI] exit returns the checker to their own tenant context
  await checker.page.getByTestId('act-as-exit').click();
  await expect(checker.page.getByTestId('act-as-banner')).toHaveCount(0);

  /* ── SCENE 3 · manufacturer personnel land in a RENDERED portal (GAP-5 closed) ── */
  const mfrAdmin = await signIn(browser, 'manufacturer admin', '/manufacturer', mfrAdminUsername, PERSON_PASS);
  const mfrChecker = await signIn(browser, 'manufacturer checker', '/manufacturer', mfrCheckerUsername, PERSON_PASS);
  await expect(mfrAdmin.page.getByText(NO_ACCESS_WALL)).toHaveCount(0);
  await expect(mfrAdmin.page.getByRole('navigation', { name: 'Portal navigation' })).toBeVisible();
  await pollUntil(async () => (await api(mfrAdmin, 'GET', '/plugins/kyc/master-values?category=ENTITY_TYPE')).status === 200,
    'kyc plugin readiness on the manufacturer');

  /* ── SCENE 4 · manufacturer onboards the DISTRIBUTOR (KYC saga; no SQL shim) ── */
  // [UI] filing goes through the real five-step form (Company · Business · Addresses ·
  // Owners · Bank accounts), and the checker approves in the real decision panel.
  const distName = `Sunrise Distribution ${s} (Pty) Ltd`;
  const distCaseId = await fileKycUi(mfrAdmin, 'manufacturer', 'DISTRIBUTOR', distName);
  await approveKycUi(mfrChecker, 'manufacturer', distCaseId);
  const { json: distView } = await api<{ caseNo: string; subjectTenantId: string }>(mfrAdmin, 'GET', `/plugins/kyc/cases/${distCaseId}`);
  const distTenantId = distView.subjectTenantId;
  await pollUntil(async () => (await api<{ status: string }>(mfrAdmin, 'GET', `/tenants/${distTenantId}`)).json.status === 'ACTIVE',
    'distributor ACTIVE');
  const distAdminUsername = sagaAdminUsername(distName, distView.caseNo);

  /* ── SCENE 5 · distributor portal, fully rendered ── */
  const distAdmin = await signIn(browser, 'distributor admin', '/distributor', distAdminUsername, PERSON_PASS);
  const distChecker = await signIn(browser, 'distributor checker', '/distributor', `checker-${distAdminUsername}`, PERSON_PASS);
  await distAdmin.page.goto('/distributor/network');
  await expect(distAdmin.page.locator('[data-widget="kyc.orgs"]')).toBeVisible();

  /* ── SCENE 6 · distributor onboards the WHOLESALER — the ladder's next rung (real form + decision panel) ── */
  // Platform → Manufacturer → Distributor → Wholesaler → Merchant (core TenantLadder): a distributor
  // cannot onboard a merchant directly any more.
  const whoName = `Erongo Wholesale ${s} (Pty) Ltd`;
  const whoCaseId = await fileKycUi(distAdmin, 'distributor', 'WHOLESALER', whoName);
  await approveKycUi(distChecker, 'distributor', whoCaseId);
  const { json: whoView } = await api<{ caseNo: string; subjectTenantId: string }>(distAdmin, 'GET', `/plugins/kyc/cases/${whoCaseId}`);
  const whoTenantId = whoView.subjectTenantId;
  await pollUntil(async () => (await api<{ status: string }>(distAdmin, 'GET', `/tenants/${whoTenantId}`)).json.status === 'ACTIVE',
    'wholesaler ACTIVE');
  const whoAdminUsername = sagaAdminUsername(whoName, whoView.caseNo);
  const whoAdmin = await signIn(browser, 'wholesaler admin', '/wholesaler', whoAdminUsername, PERSON_PASS);
  const whoChecker = await signIn(browser, 'wholesaler checker', '/wholesaler', `checker-${whoAdminUsername}`, PERSON_PASS);
  await pollUntil(async () => (await api(whoAdmin, 'GET', '/plugins/kyc/master-values?category=ENTITY_TYPE')).status === 200,
    'kyc plugin readiness on the wholesaler');

  /* ── SCENE 6b · wholesaler onboards the MERCHANT (the leaf) ── */
  const merName = `Namib Fresh Produce ${s} (Pty) Ltd`;
  const merCaseId = await fileKycUi(whoAdmin, 'wholesaler', 'MERCHANT', merName);
  await approveKycUi(whoChecker, 'wholesaler', merCaseId);
  const { json: merView } = await api<{ caseNo: string; subjectTenantId: string }>(whoAdmin, 'GET', `/plugins/kyc/cases/${merCaseId}`);
  const merTenantId = merView.subjectTenantId;
  await pollUntil(async () => (await api<{ status: string }>(whoAdmin, 'GET', `/tenants/${merTenantId}`)).json.status === 'ACTIVE',
    'merchant ACTIVE');
  const merAdminUsername = sagaAdminUsername(merName, merView.caseNo);

  /* ── SCENE 7 · inside the merchant: a NAMED checker user, four-eyes, in the merchant portal ── */
  const merAdmin = await signIn(browser, 'merchant admin', '/merchant', merAdminUsername, PERSON_PASS);
  const merChecker = await signIn(browser, 'merchant checker', '/merchant', `checker-${merAdminUsername}`, PERSON_PASS);
  await expect(merAdmin.page.getByText(NO_ACCESS_WALL)).toHaveCount(0); // GAP-6 closed
  const { json: roleOptions } = await api<{ id: string; key: string }[]>(merAdmin, 'GET', `/tenants/${merTenantId}/user-roles`);
  const named = `tulonga.checker.${s}`;
  const staged = await api(merAdmin, 'POST', `/tenants/${merTenantId}/users`, {
    username: named, fullName: 'Tulonga Shipanga', email: `${named}@namibfresh.example`, primaryContact: 'EMAIL',
    roleIds: [roleOptions.find((r) => r.key === 'TENANT_CHECKER')!.id], checker: true,
  });
  expect(staged.status, 'checker user staged').toBe(202);
  await merAdmin.page.goto(`/merchant/users?q=${named}`);
  await expect(merAdmin.page.getByText('Awaiting approval').first()).toBeVisible(); // GAP-8 closed
  const userCreate = await pendingApproval(merChecker, merTenantId, 'USER_CREATE');
  await approveInUi(merChecker, '/merchant', userCreate!.id, 'e2e: named checker'); // GAP-3 closed
  const tulonga = await signIn(browser, 'named checker', '/merchant', named, PERSON_PASS);
  expect((await sessionUser(tulonga)).username).toBe(named);
  await expect(tulonga.page.getByText(NO_ACCESS_WALL)).toHaveCount(0);

  /* ── SCENE 8 · first money: CoA → balanced JE → four-eyes → FINALIZED (real clicks) ── */
  // [UI] ledger.accounts screen provisions the default chart on the ACTIVE book.
  const ledgerPage = merAdmin.page;
  await ledgerPage.goto('/merchant/ledger');
  // the composed screen carries the widget's own header too — same accessible name twice
  await expect(ledgerPage.getByRole('heading', { name: 'Chart of accounts' }).first()).toBeVisible();
  const provision = ledgerPage.locator('[data-action="coa.provision"]');
  // empty book → the provision offer; already-provisioned book → the accounts table
  await expect(provision.or(ledgerPage.getByRole('table'))).toBeVisible({ timeout: 60_000 });
  if (await provision.isVisible().catch(() => false)) {
    await provision.click(); // fresh merchant → empty book → the offer is shown
  }
  await expect(ledgerPage.getByRole('table').locator('tbody tr')).not.toHaveCount(0, { timeout: 90_000 });

  // [UI] maker creates the balanced DRAFT entry in the journal.entry form (today: open period).
  const jePage = merAdmin.page;
  await jePage.goto('/merchant/journal');
  await jePage.getByRole('button', { name: 'New entry' }).click();
  const line = (n: 1 | 2) => jePage.locator('fieldset', { hasText: `Line ${n}` });
  const accountsSelect = line(1).getByLabel('Account');
  const optionCount = await accountsSelect.locator('option').count(); // includes the “Choose an account” placeholder
  expect(optionCount, 'the default chart is postable-ready').toBeGreaterThanOrEqual(3);
  await line(1).getByLabel('Account').selectOption({ index: 1 }); // lowest postable code (asset side)
  await line(1).getByLabel('Side').selectOption('DEBIT');
  await line(1).getByLabel(/^Amount \(/).fill('2500.00');
  await line(2).getByLabel('Account').selectOption({ index: optionCount - 1 }); // highest code (revenue side)
  await line(2).getByLabel('Side').selectOption('CREDIT');
  await line(2).getByLabel(/^Amount \(/).fill('2500.00');
  await jePage.getByLabel('Description').fill('Opening float');
  await jePage.getByLabel(/Reference/).fill(`e2e-${randomUUID().slice(0, 8)}`);
  await jePage.getByRole('button', { name: 'Save draft' }).click();
  // create returns the entry selected in the URL → the detail panel opens as DRAFT
  await expect(jePage.locator('[data-posting-state="DRAFT"]').first()).toBeVisible({ timeout: 60_000 });
  const entryId = new URL(jePage.url()).searchParams.get('entry');
  expect(entryId, 'draft creation selects the new entry').toBeTruthy();

  // [UI] maker submits for checker approval.
  await jePage.locator('[data-action="je.submit"]').click();
  await expect(jePage.locator('[data-posting-state="PENDING_CHECKER"]').first()).toBeVisible({ timeout: 60_000 });

  // [UI] merchant auto-checker (four-eyes, different person) approves and posts.
  const checkerPage = merChecker.page;
  await checkerPage.goto('/merchant/journal?status=PENDING_CHECKER');
  await checkerPage.getByRole('button', { name: /Opening float/ }).click();
  await checkerPage.locator('[data-action="je.approve"]').click();
  await checkerPage.getByRole('alertdialog').getByRole('button', { name: 'Approve and post' }).click();
  await pollUntil(async () => (await api<{ status: string }>(merChecker, 'GET', `/tenants/${merTenantId}/journal-entries/${entryId}`)).json.status === 'FINALIZED',
    'merchant JE FINALIZED (ledger commit confirmed)');
  const { json: books } = await api<{ id: string; status: string }[]>(merAdmin, 'GET', `/tenants/${merTenantId}/books`);
  const bookId = books.find((b) => b.status === 'ACTIVE')!.id;
  await merAdmin.page.goto(`/merchant/journal?entry=${entryId}`);
  await expect(merAdmin.page.locator('[data-posting-state="FINALIZED"]').first()).toBeVisible();

  /* ── SCENE 8.5 · settlement bank accounts (F-10): maker files in the UI, checker approves ── */
  // [UI] the merchant admin (maker, bank.account.create) files the org's payout account.
  const bankPage = merAdmin.page;
  await bankPage.goto('/merchant/bank-accounts');
  await expect(bankPage.getByRole('heading', { name: 'Settlement bank accounts' })).toBeVisible();
  await bankPage.locator('[data-action="bank.account.file"]').click();
  await bankPage.getByTestId('bank-holder').fill('Namib Fresh Produce (Pty) Ltd');
  await bankPage.getByTestId('bank-code').selectOption({ label: 'First National Bank Namibia' });
  await bankPage.getByTestId('bank-branch').fill('280172');
  await bankPage.getByTestId('bank-account-number').fill(`9900${s}77`.replace(/[^0-9]/g, '').padEnd(8, '7').slice(0, 16));
  await bankPage.getByTestId('bank-account-type').selectOption('SETTLEMENT');
  await bankPage.locator('[data-action="bank.account.request"]').click();
  await expect(bankPage.getByTestId('bank-row-pending')).toBeVisible({ timeout: 30_000 });
  await expect(bankPage.getByTestId('bank-pending-banner')).toBeVisible(); // explicit pending path (ui-std §5.1)
  // the wire never carries the full number — masked to last 4 everywhere (AGENTS §2c)
  const { json: bankList } = await api<{ items: Array<{ id: string; status: string; maskedAccountNumber: string }> }>(
    merAdmin, 'GET', `/tenants/${merTenantId}/bank-accounts`);
  const filed = bankList.items.find((b) => b.status === 'PENDING');
  expect(filed, 'a PENDING bank account exists').toBeDefined();
  expect(filed!.maskedAccountNumber.length, 'account number is masked on the wire').toBeLessThanOrEqual(10);
  const bankAccountId = filed!.id;

  // [API] four-eyes: the maker cannot approve their own filing (explicit refusal, not silence).
  const makerOwnBank = await pendingApproval(merAdmin, merTenantId, 'BANK_ACCOUNT_CREATE');
  expect(makerOwnBank, "the maker's filing is a four-eyes approval").toBeDefined();
  const selfBank = await api(merAdmin, 'POST', `/approvals/${makerOwnBank!.id}/approve`, { reason: 'e2e: self' });
  expect(selfBank.status, 'maker self-approve refused').toBe(403);

  // ── SCENE 8.6 · checker notifications (F-12): an open approval PUSHES to the checker ──
  // [API] the checker plane was notified on open — one deduped pointer per checker, generic copy.
  const notifUnread = await api<{ unread: number }>(merChecker, 'GET', '/notifications/unread-count');
  expect(notifUnread.json.unread, 'the checker has unread approval notifications').toBeGreaterThanOrEqual(1);
  const notifBox = await api<{ items: Array<{ type: string; opType: string; body: string; link: string }> }>(
    merChecker, 'GET', '/notifications');
  const bankNotif = notifBox.json.items.find((n) =>
    n.type === 'APPROVAL_OPEN' && n.opType === 'BANK_ACCOUNT_CREATE');
  expect(bankNotif, 'an APPROVAL_OPEN notification points at the bank filing').toBeDefined();
  expect(bankNotif!.body.toLowerCase()).toContain('bank');
  expect(bankNotif!.body, 'no account digits leak into the notification (AGENTS §2c)')
    .not.toMatch(new RegExp(filed!.maskedAccountNumber.slice(-4)));
  // [UI] the badge on the Approvals nav item is visible without visiting the queue.
  const merCheckerShell = merChecker.page;
  await merCheckerShell.goto('/merchant/approvals');
  await expect(merCheckerShell.getByTestId('approvals-badge')).toBeVisible({ timeout: 15_000 });

  // [UI] the merchant checker (bank.account.approve) decides it in Approvals — one click.
  const bankApproval = await pendingApproval(merChecker, merTenantId, 'BANK_ACCOUNT_CREATE');
  await approveInUi(merChecker, '/merchant', bankApproval!.id, 'e2e: settlement bank account');
  await pollUntil(async () =>
    (await api<{ items: Array<{ id: string; status: string }> }>(merAdmin, 'GET', `/tenants/${merTenantId}/bank-accounts`))
      .json.items.some((b) => b.id === bankAccountId && b.status === 'ACTIVE'),
    'merchant settlement bank account ACTIVE');
  await bankPage.reload();
  await expect(bankPage.getByTestId('bank-row-active')).toBeVisible();
  await expect(bankPage.getByTestId('bank-pending-banner')).toHaveCount(0); // the pending path cleared

  // [API/UI] F-12 tail (V38): deciding the request is what clears it — the bank pointer leaves
  // the checker's PENDING inbox (read-all still marks read, but no longer hides work).
  const pendingAfter = await api<{ items: Array<{ type: string; opType: string }>; pending: number }>(
    merChecker, 'GET', '/notifications?state=pending&size=100');
  expect(pendingAfter.json.items.some((n) => n.type === 'APPROVAL_OPEN' && n.opType === 'BANK_ACCOUNT_CREATE'),
    'the decided bank approval no longer asks the checker to act').toBe(false);
  const clearInbox = await api(merChecker, 'POST', '/notifications/read-all');
  expect(clearInbox.status, 'inbox marked read').toBeLessThan(300);
  const unreadCleared = await api<{ unread: number }>(merChecker, 'GET', '/notifications/unread-count');
  expect(unreadCleared.json.unread).toBe(0);
  if (pendingAfter.json.pending === 0) {
    await merCheckerShell.reload();
    await expect(merCheckerShell.getByTestId('approvals-badge')).toHaveCount(0);
  }

  // [API] the PLATFORM ROOT's own account: filed by a platform admin, approved by the
  // platform team's checker (different person) — same machinery at the root tenant.
  const rootFile = await api<{ status: string }>(maker, 'POST', `/tenants/${PLATFORM_TENANT_ID}/bank-accounts`, {
    holderName: 'FSP Platform (Pty) Ltd', bankCode: 'BANK_WINDHOEK', branchCode: '481972',
    accountNumber: '2000000000123456'.slice(0, 16), accountType: 'SETTLEMENT', currency: 'NAD',
  });
  if (rootFile.status === 201 || rootFile.status === 200) {
    await approveVia(checker, PLATFORM_TENANT_ID, 'BANK_ACCOUNT_CREATE', 'platform settlement bank account');
    const { json: rootList } = await api<{ items: Array<{ status: string }> }>(
      maker, 'GET', `/tenants/${PLATFORM_TENANT_ID}/bank-accounts`);
    expect(rootList.items.some((b) => b.status === 'ACTIVE'), 'platform root has an ACTIVE settlement account').toBe(true);
  } else {
    // idempotent across runs: an ACTIVE root account already exists — nothing to approve
    const { json: rootList } = await api<{ items: Array<{ status: string }> }>(
      maker, 'GET', `/tenants/${PLATFORM_TENANT_ID}/bank-accounts`);
    expect(rootList.items.some((b) => b.status === 'ACTIVE'), 'platform root already has an ACTIVE account').toBe(true);
  }

  /* ── SCENE 8.7 · first-login password change (F-8): an API-created person sets their own ── */
  // A person born with the bootstrap password is flagged `password_must_change` — the IdP
  // hosted surface forces a change before any session is minted (credentials never leave the IdP,
  // UI standard: no shared-secret survival). Honest setup: an API-created USER via four-eyes.
  const pwdUsername = `pwd.chg.${s}`;
  expect(roleOptions.length, 'merchant tenant offers assignable roles').toBeGreaterThanOrEqual(1);
  const pwdCreate = await api<{ personId: string; username: string }>(merAdmin, 'POST', `/tenants/${merTenantId}/users`, {
    fullName: 'Password Flow User', username: pwdUsername, email: `${pwdUsername}@namibfresh.example`, primaryContact: 'EMAIL', roleIds: [roleOptions[0].id],
  });
  expect(pwdCreate.status, 'password-flow user staged').toBe(202);
  await approveVia(merChecker, merTenantId, 'USER_CREATE', 'password-flow user');
  // first sign-in with the bootstrap password lands on the IdP change form — no session yet
  const pwdPage = await signIn(browser, 'password flow', '/merchant', pwdUsername, PERSON_PASS, { leaveChangePending: true });
  expect(pwdPage.pendingChange, 'first sign-in lands on the IdP change-password form').toBe(true);
  const chgPage = pwdPage.page;
  const pwdNewPassword = `E2e-${s}!pw`;
  await expect(chgPage.getByRole('heading', { name: 'Set your own password' })).toBeVisible();
  // too short — rejected on the host, no code minted, the same ticket survives
  await completePasswordChange(chgPage, 'short1');
  await expect(chgPage.locator('#chg-err')).toContainText('10');
  // mismatch — rejected
  await completePasswordChange(chgPage, pwdNewPassword, pwdNewPassword + 'x');
  await expect(chgPage.locator('#chg-err')).toContainText('not match');
  // same as the temporary one — rejected
  await completePasswordChange(chgPage, PERSON_PASS);
  await expect(chgPage.locator('#chg-err')).toContainText('not used here');
  // a real password — accepted; the parked sign-in resumes into the portal
  await completePasswordChange(chgPage, pwdNewPassword);
  await chgPage.waitForURL((u) => u.pathname.startsWith('/merchant'), { timeout: 30_000 });
  // the bootstrap password is dead — re-signin fails explicitly at the IdP
  // (fresh context: the change-completion above authenticated the parked one)
  const staleContext = await browser.newContext({ baseURL: 'http://localhost:5173' });
  const stale = await staleContext.newPage();
  await stale.goto('/merchant/login');
  await stale.waitForSelector('#u', { timeout: 30_000 });
  await stale.fill('#u', pwdUsername);
  await stale.fill('#p', PERSON_PASS);
  await stale.click('button:has-text("Sign in")');
  await stale.waitForURL((u) => u.search.includes('authentication_failed'), { timeout: 15_000 });
  // and the replacement password signs in cleanly (fresh context, new session)
  const reSignIn = await signIn(browser, 'password flow re-signin', '/merchant', pwdUsername, pwdNewPassword);
  expect((await sessionUser(reSignIn)).username).toBe(pwdUsername);

  /* ── SCENE 9 · curtain call ── */
  console.log('\n=== ONBOARDING CHAIN COMPLETE ===\n', JSON.stringify({
    manufacturer: mfrTenantId, distributor: distTenantId, merchant: merTenantId, book: bookId, entry: entryId,
    users: { mfrAdminUsername, mfrCheckerUsername, distAdminUsername, merAdminUsername, named },
  }, null, 2));
  await merAdmin.page.screenshot({ path: 'test-results/chain-final.png', fullPage: true });
});
