/**
 * F-9 · the onboarding story's UNHAPPY twin — every negative path with its explicit
 * ui-std §5.2 UI state, never a silent no-op. Live stack; serial because the scenes
 * share the manufacturer stage and the merchant org approved in scene A.
 *
 *  A. KYC REQUEST_INFO loop — checker returns the case, the maker amends + resubmits
 *     (saga resume: re-screened server-side, a NEW approval revision opens), checker
 *     approves → tenant ACTIVE. Until the saga `resume` step existed this loop died in
 *     SUBMITTED with no re-opened approval (stalled forever).
 *  B. KYC REJECT — the tenant is NOT activated; the rejected case is terminal (409).
 *  C. Self-approve is blocked as a STATE, not hidden: the maker sees the four-eyes banner
 *     (KYC DecisionPanel + JE detail), and the API answers 403 MAKER_CANNOT_APPROVE.
 *  D. Stalled-saga recovery — a case stuck TENANT_PENDING > 10 min becomes STALLED
 *     (the recovery job's operator-visible state).
 */
import { execSync } from 'node:child_process';
import { expect, test } from '@playwright/test';
import {
  api, BOOT_PASS, createManufacturer, ensureKycPlugin, kycApplication, pendingApproval,
  PERSON_PASS, pollUntil, sagaAdminUsername, sessionUser, signIn, suffix,
} from './support';

test.describe.configure({ mode: 'serial' });

function code(json: unknown): string {
  return (json as { code?: string } | null)?.code ?? '';
}

function decide(s: Parameters<typeof api>[0], caseId: string, decision: string, reason?: string) {
  return api<{ status: string; version: number; approvalRev?: number }>(
    s, 'POST', `/plugins/kyc/cases/${caseId}/decisions`, { decision, reason: reason ?? null });
}

test('unhappy twin — REQUEST_INFO loop, REJECT, self-approve blocks, stalled recovery', async ({ browser }) => {
  test.setTimeout(360_000);

  /* ── SETUP · platform opens the show; one manufacturer stage (plugin + personas) ── */
  const platformAdmin = await signIn(browser, 'platform maker', '/admin', 'platform-admin', BOOT_PASS);
  const platformChecker = await signIn(browser, 'platform checker', '/admin', 'admin-checker', BOOT_PASS);
  await ensureKycPlugin(platformAdmin, platformChecker);
  const mfr = await createManufacturer(platformAdmin, platformChecker);
  const mfrAdmin = await signIn(browser, 'manufacturer admin', '/manufacturer', mfr.adminUsername, PERSON_PASS);
  const mfrChecker = await signIn(browser, 'manufacturer checker', '/manufacturer', mfr.checkerUsername, PERSON_PASS);
  const mfrTenantId = (await sessionUser(mfrAdmin)).tenantId;
  await pollUntil(async () => (await api(mfrAdmin, 'GET', '/plugins/kyc/master-values?category=ENTITY_TYPE')).status === 200,
    'kyc plugin readiness on the manufacturer');

  /* ── A · REQUEST_INFO: the returned case loops once more and is then approved ── */
  const nameA = `Two Rivers Trading ${suffix()} (Pty) Ltd`;
  const filedA = await api<{ caseId: string; subjectTenantId: string; caseNo: string; status: string; version: number }>(
    mfrAdmin, 'POST', '/plugins/kyc/cases', kycApplication('DISTRIBUTOR', nameA));
  expect(filedA.status, `case filed: ${JSON.stringify(filedA.json)}`).toBe(201);
  const caseIdA = filedA.json.caseId;
  const tenantA = filedA.json.subjectTenantId;
  expect(filedA.json.status).toBe('PENDING_REVIEW');

  // checker returns the case with a reason (10+ chars — what the applicant reads)
  const info = await decide(mfrChecker, caseIdA, 'REQUEST_INFO', 'The registered address street details are missing.');
  expect(info.status, `REQUEST_INFO decided: ${JSON.stringify(info.json)}`).toBe(200);
  expect(info.json.status).toBe('REQUEST_INFO');
  // the child is NOT verified on a return
  const t1 = await api<{ status: string; verificationStatus: string | null }>(mfrAdmin, 'GET', `/tenants/${tenantA}`);
  expect(t1.json.status).not.toBe('ACTIVE');

  // maker amends + resubmits → the saga resumes: re-screen + NEW approval revision
  const amended = await api(mfrAdmin, 'PATCH', `/plugins/kyc/cases/${caseIdA}`, {
    expectedVersion: info.json.version, registeredCity: 'Swakopmund', registeredPostal: '13001',
  });
  expect(amended.status, `amend: ${JSON.stringify(amended.json)}`).toBe(200);
  const resub = await api<{ status: string; approvalRev: number }>(mfrAdmin, 'POST', `/plugins/kyc/cases/${caseIdA}/submit`, {});
  expect(resub.status, `resubmit resumed the saga: ${JSON.stringify(resub.json)}`).toBe(200);
  expect(resub.json.status, 'case is under fresh review').toBe('PENDING_REVIEW');
  expect(resub.json.approvalRev, 'a NEW approval revision was opened (never 1 again)').toBe(2);
  // the fresh approval is on the manufacturer's queue for the checker
  const fresh = await pendingApproval(mfrChecker, mfrTenantId, 'PLUGIN:kyc:CASE_DECISION');
  expect(fresh, 'resubmission opened a fresh four-eyes approval').toBeDefined();

  const approved = await decide(mfrChecker, caseIdA, 'APPROVE', 'Amended data verified, screening clean.');
  expect(approved.status).toBe(200);
  await pollUntil(async () => (await api<{ status: string }>(mfrAdmin, 'GET', `/tenants/${tenantA}`)).json.status === 'ACTIVE',
    'approved after one REQUEST_INFO loop → tenant ACTIVE');

  /* ── B · REJECT: terminal verdict, no activation, no second decision ── */
  const nameB = `Kuiseb Logistics ${suffix()} (Pty) Ltd`;
  const filedB = await api<{ caseId: string; subjectTenantId: string }>(
    mfrAdmin, 'POST', '/plugins/kyc/cases', kycApplication('DISTRIBUTOR', nameB));
  expect(filedB.status).toBe(201);
  const rejected = await decide(mfrChecker, filedB.json.caseId, 'REJECT', 'Watch-list hit on the listed UBO, unresolved.');
  expect(rejected.status, `rejected: ${JSON.stringify(rejected.json)}`).toBe(200);
  expect(rejected.json.status).toBe('REJECTED');
  const tRejected = await api<{ status: string; verificationStatus: string | null }>(mfrAdmin, 'GET', `/tenants/${filedB.json.subjectTenantId}`);
  expect(tRejected.json.status, 'a rejected applicant org is NEVER activated').not.toBe('ACTIVE');
  expect(tRejected.json.verificationStatus !== 'VERIFIED').toBe(true);
  const again = await decide(mfrChecker, filedB.json.caseId, 'APPROVE', 'late change of heart');
  expect(again.status, 'terminal cases are closed to further decisions').toBe(409);
  expect(code(again.json)).toBe('KYC_STATE');

  /* ── C · self-approve is blocked as a STATE, not hidden (ui-std §5.2) ── */
  // C0 · filing needs the maker permission — a checker cannot open a case at all.
  const checkerFiles = await api(mfrChecker, 'POST', '/plugins/kyc/cases', kycApplication('DISTRIBUTOR', `Not A Filer ${suffix()}`));
  expect(checkerFiles.status, 'filing needs org.onboard_child (the maker side)').toBe(403);
  expect(code(checkerFiles.json)).toBe('PERMISSION_DENIED');

  // C1 · KYC: the admin files (they hold org.onboard_child); approving their own case is
  // refused by the API and, server-side, the viewer's canDecide=false/block=MAKER. The review
  // queue UI is the checker's positive control (a NON-maker renders the decision controls).
  const nameC = `Onyx Crafts ${suffix()} (Pty) Ltd`;
  const byAdmin = await api<{ caseId: string }>(mfrAdmin, 'POST', '/plugins/kyc/cases', kycApplication('DISTRIBUTOR', nameC));
  expect(byAdmin.status, 'the admin (maker side) may file').toBe(201);
  const makerApiTry = await decide(mfrAdmin, byAdmin.json.caseId, 'APPROVE', 'trying to bless my own filing');
  expect(makerApiTry.status, `maker decide refused: ${JSON.stringify(makerApiTry.json)}`).toBe(403);
  // born-role admins hold kyc.review but NOT kyc.approve — the edge refuses first; the same
  // person-level four-eyes refusal (MAKER_CANNOT_APPROVE) is asserted in C3 where it is reachable.
  expect(code(makerApiTry.json)).toBe('PERMISSION_DENIED');
  expect(String(makerApiTry.json?.permission ?? '')).toBe('kyc.approve');
  const makerView = await api<{ canDecide: boolean; blockedReason: string | null; status: string }>(
    mfrAdmin, 'GET', `/plugins/kyc/cases/${byAdmin.json.caseId}`);
  expect(makerView.status).toBe(200);
  expect(makerView.json.status).toBe('PENDING_REVIEW');
  expect(makerView.json.canDecide, 'the maker is told they cannot decide').toBe(false);
  expect(makerView.json.blockedReason).toBe('MAKER');
  // the checker sees the case WITH decision controls (the server drives the panel)
  const checkerView = await api<{ canDecide: boolean; blockedReason: string | null }>(
    mfrChecker, 'GET', `/plugins/kyc/cases/${byAdmin.json.caseId}`);
  expect(checkerView.json.canDecide).toBe(true);
  await mfrChecker.page.goto(`/manufacturer/kyc/reviews?case=${byAdmin.json.caseId}`);
  await expect(mfrChecker.page.locator('[data-action="kyc.approve"]')).toBeVisible();
  await expect(mfrChecker.page.locator('[data-action="kyc.reject"]')).toBeVisible();
  // the maker-blocked case is left for a later reviewer; it is not silently closed
  expect((await api<{ status: string }>(mfrAdmin, 'GET', `/plugins/kyc/cases/${byAdmin.json.caseId}`)).json.status)
    .toBe('PENDING_REVIEW');

  // C2 · JE: enter the (now ACTIVE) org of scene A — a DISTRIBUTOR, the manufacturer's only
  // allowed child (core TenantLadder) — in its own portal; the maker tries to approve.
  const merAdminUsername = sagaAdminUsername(nameA, filedA.json.caseNo);
  const merAdmin = await signIn(browser, 'distributor admin', '/distributor', merAdminUsername, PERSON_PASS);
  const merChecker = await signIn(browser, 'distributor checker', '/distributor', `checker-${merAdminUsername}`, PERSON_PASS);
  const merTenantId = (await sessionUser(merAdmin)).tenantId;
  const books = await api<{ id: string; status: string }[]>(merAdmin, 'GET', `/tenants/${merTenantId}/books`);
  const bookId = books.json.find((b) => b.status === 'ACTIVE')!.id;
  expect((await api(merAdmin, 'POST', `/tenants/${merTenantId}/books/${bookId}/provision-default-coa`, {})).status)
    .toBeLessThan(300);
  const gl = await api<{ id: string }[]>(merAdmin, 'GET', `/tenants/${merTenantId}/books/${bookId}/gl-accounts`);
  const day = new Date().toISOString().slice(0, 10);
  const je = await api<{ entryId: string }>(merAdmin, 'POST', `/tenants/${merTenantId}/journal-entries`, {
    bookId, postingDate: day, valueDate: day, description: `unhappy ${suffix()}`,
    lines: [
      { glAccountId: gl.json[0].id, direction: 'DEBIT', amount: '10000', memo: 'maker side' },
      { glAccountId: gl.json[gl.json.length - 1].id, direction: 'CREDIT', amount: '10000', memo: 'maker side' },
    ],
  });
  expect(je.status, `JE created: ${JSON.stringify(je.json)}`).toBeLessThan(300);
  const entryId = je.json.entryId;
  expect((await api(merAdmin, 'POST', `/tenants/${merTenantId}/journal-entries/${entryId}/submit-for-approval`, {})).status)
    .toBeLessThan(300);
  // API lane: the maker's own approve is refused — admins hold je.create, not je.approve
  // (role separation by design); the reachable person-level four-eyes refusal is C3.
  const selfApprove = await api(merAdmin, 'POST', `/tenants/${merTenantId}/journal-entries/${entryId}/approve`, {});
  expect(selfApprove.status, `maker JE approve refused: ${JSON.stringify(selfApprove.json)}`).toBe(403);
  expect(code(selfApprove.json)).toBe('PERMISSION_DENIED');
  // UI lane: the maker's detail shows the four-eyes banner, never an approve control
  await merAdmin.page.goto(`/distributor/journal?entry=${entryId}`);
  await expect(merAdmin.page.locator('[data-posting-state="PENDING_CHECKER"]').first()).toBeVisible();
  await expect(merAdmin.page.getByText('so you can’t approve it')).toBeVisible();
  await expect(merAdmin.page.locator('[data-action="je.approve"]')).toHaveCount(0);
  // four-eyes is additive, not an amputation: the checker CAN decide it
  expect((await api(merChecker, 'POST', `/tenants/${merTenantId}/journal-entries/${entryId}/approve`, {})).status)
    .toBeLessThan(300);
  await pollUntil(async () =>
    (await api<{ status: string }>(merAdmin, 'GET', `/tenants/${merTenantId}/journal-entries/${entryId}`)).json.status === 'FINALIZED',
    'checker-approved entry FINALIZED');

  // C3 · approval-lane four-eyes: the maker approves their own USER_CREATE → refused.
  const roles = await api<{ id: string; key: string }[]>(merAdmin, 'GET', `/tenants/${merTenantId}/user-roles`);
  const checkerRole = roles.json.find((r) => r.key === 'TENANT_CHECKER')!.id;
  const second = `second.checker.${suffix()}`;
  const staged = await api<{ id: string }>(merAdmin, 'POST', `/tenants/${merTenantId}/users`, {
    username: second, fullName: 'Second Checker', email: `${second}@unhappy.example`, primaryContact: 'EMAIL',
    roleIds: [checkerRole], checker: true,
  });
  expect(staged.status).toBe(202);
  const userCreate = await pendingApproval(merChecker, merTenantId, 'USER_CREATE');
  expect(userCreate, 'USER_CREATE approval on the merchant queue').toBeDefined();
  const selfUserApprove = await api(merAdmin, 'POST', `/approvals/${userCreate!.id}/approve`, { reason: 'e2e: self-approve' });
  expect(selfUserApprove.status, `self USER_CREATE approve refused: ${JSON.stringify(selfUserApprove.json)}`).toBe(403);
  expect(code(selfUserApprove.json)).toBe('MAKER_CANNOT_APPROVE');
  expect((await api(merChecker, 'POST', `/approvals/${userCreate!.id}/approve`, { reason: 'e2e: proper four-eyes' })).status)
    .toBe(200);

  /* ── D · stalled-saga recovery: TENANT_PENDING > 10 min → STALLED (operator-visible) ── */
  execSync(
    `PGPASSWORD=fsp psql -h 127.0.0.1 -U fsp -d fsp_plugin_kyc -c "UPDATE kyc_cases SET status='TENANT_PENDING', updated_at = now() - interval '11 minutes' WHERE id='${filedB.json.caseId}'"`,
    { stdio: 'ignore' });
  await pollUntil(async () =>
    (await api<{ status: string }>(mfrAdmin, 'GET', `/plugins/kyc/cases/${filedB.json.caseId}`)).json.status === 'STALLED',
    'recovery job surfaced the stuck saga as STALLED', 90, 2_000);
  const stalled = await api<{ items?: { status: string }[]; content?: { status: string }[] }>(
    mfrAdmin, 'GET', '/plugins/kyc/cases?status=STALLED');
  const all = [...(stalled.json.items ?? []), ...(stalled.json.content ?? [])];
  expect(all.some((c) => c.status === 'STALLED'), 'STALLED is a first-class, filterable operator state').toBe(true);
});
