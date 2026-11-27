/**
 * org.branches — end-to-end spec (fsp-widget skill).
 *
 *   workbench lane — fixtures (stateful fixture-server.ts), every scenario × every tier, geometry,
 *                    permission gating, every action reachable with its cell, and the journeys:
 *                    list + search/filter/sort/page · maker files create / rename / delete ·
 *                    checker approves / rejects · four-eyes refusals shown · viewer is read-only
 *   portal lane    — real BFF sign-in + the real screen; NEW operations answered from fixtures
 *                    until FSP_WIDGET_LIVE=1 (stage 2: nothing stubbed)
 *
 * Run: bun run widgets:e2e org.branches [--lane workbench|portal|all] [--live]
 */
import { expect, test, type Page } from '@playwright/test';
import {
  expectActionsWith, expectGatedWithoutCells, openScreen, openWorkbench, portalSession, stageOf, stubPlannedOps, sweepScenarios,
  type Scenario,
} from '../widget-support';
import { fixtures } from '../../../src/portals/shared/widgets/org/branches/data';
import { COOLDOWN_MS } from '../../../src/portals/shared/web-core/submit-guard';

const ID = 'org.branches';
const LANES = (process.env.FSP_WIDGET_LANES ?? 'workbench,portal').split(',');
/** create is on the list; the rest live in the branch drawer (covered by the drawer tests below) */
const LIST_ACTIONS: { id: string; cell: string; scenarios?: Scenario[] }[] = [{ id: 'branch.create', cell: 'role.manage' }];
const ALL_ACTIONS = ['branch.create', 'branch.edit', 'branch.delete', 'branch.approve', 'branch.reject'];
const REQUIRES: string[] = ['role.view', 'role.manage'];
const MANAGER = ['role.view', 'role.manage'];

/** fixture branches (fixture-server.ts) */
const OSHAKATI = 'BR-0003'; // PENDING create, filed by Johannes → decidable
const SWAKOP = 'BR-0004'; // rename filed by the signed-in user → OWN_REQUEST
const KEET = 'BR-0005'; // deletion filed by Maria → decidable
const KATIMA = 'BR-0008'; // ACTIVE, nothing pending

/** the global submit guard drops a repeat press of the same button/form inside COOLDOWN_MS —
 *  a person retyping a name never hits it, a test re-submitting at machine speed does */
const pastCooldown = (page: Page) => page.waitForTimeout(COOLDOWN_MS + 100);

const md = (page: Page) => page.locator('[data-tier="md"] [data-cell]');
const rows = (page: Page) => md(page).locator('[data-testid^="branch-row-"]');
const dialog = (page: Page) => page.getByRole('dialog');

async function openRow(page: Page, code: string, cells: string[] = MANAGER) {
  await openWorkbench(page, ID, { tier: 'md', cells });
  await md(page).getByTestId(`branch-row-${code}`).click();
  await expect(dialog(page)).toBeVisible();
}

test.describe(`${ID} · workbench (fixtures)`, () => {
  test.skip(!LANES.includes('workbench'), 'workbench lane not selected');

  test('every scenario renders its state at every tier, without layout faults', async ({ page }) => {
    await sweepScenarios(page, ID);
  });

  test('without its cells the widget is forbidden and no action is reachable', async ({ page }) => {
    await expectGatedWithoutCells(page, ID, ALL_ACTIONS);
  });

  for (const a of LIST_ACTIONS) {
    test(`action ${a.id} shows with ${a.cell}`, async ({ page }) => {
      await expectActionsWith(page, ID, [...new Set([a.cell, ...REQUIRES])], [a.id], a.scenarios);
    });
  }

  test('actions branch.edit + branch.delete show in the drawer with role.manage', async ({ page }) => {
    await openRow(page, KATIMA);
    await expect(dialog(page).locator('[data-action="branch.edit"]')).toBeEnabled();
    await expect(dialog(page).locator('[data-action="branch.delete"]')).toBeEnabled();
  });

  test('actions branch.approve + branch.reject show in the drawer for a checker', async ({ page }) => {
    await openRow(page, OSHAKATI);
    await expect(dialog(page).locator('[data-action="branch.approve"]')).toBeVisible();
    await expect(dialog(page).locator('[data-action="branch.reject"]')).toBeVisible();
  });

  test('list shows name, who created, who approved and when — searched, filtered, sorted and paged on the server', async ({ page }) => {
    await openWorkbench(page, ID, { tier: 'md', cells: MANAGER });
    const w = md(page);
    await expect(w.getByRole('heading', { name: 'Branches' })).toBeVisible();
    const katima = w.getByTestId(`branch-row-${KATIMA}`);
    await expect(katima).toContainText('Katima Mulilo');
    await expect(katima).toContainText('Peter Amutenya'); // created by
    await expect(katima).toContainText('Anna Shikongo'); // approved by
    await expect(katima).toContainText(/\d{1,2} \w{3} 2026, \d{2}:\d{2}/); // created date + time
    await expect(w.getByTestId(`branch-row-${OSHAKATI}`)).toContainText('Awaiting checker');

    // paging: 24 current branches, 20 per page
    await expect(rows(page)).toHaveCount(20);
    await expect(w.getByText('1–20 of 24 branches')).toBeVisible();
    await w.getByRole('button', { name: /Next/ }).click();
    await expect(rows(page)).toHaveCount(4);
    await expect(w.getByText('21–24 of 24 branches')).toBeVisible();

    // search (debounced, min 2 chars) resets to page 1
    const search = w.getByTestId('branch-search');
    await search.fill('w');
    await expect(w.getByTestId('branch-search-hint')).toBeVisible();
    await search.fill('walvis');
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('Walvis Bay');
    // (each workbench frame has its own in-memory router, so the ?q= URL state is checked in the portal lane)
    await search.press('Escape');
    await expect(search).toHaveValue('');
    await expect(rows(page)).toHaveCount(20);

    // status filter
    await w.getByTestId('branch-filter-awaiting').click();
    await expect(rows(page)).toHaveCount(3);
    await expect(w.getByTestId(`branch-row-${SWAKOP}`)).toContainText('Rename awaiting approval');
    await expect(w.getByTestId(`branch-row-${SWAKOP}`)).toContainText('→ Swakopmund Town Centre');
    await expect(w.getByTestId(`branch-row-${KEET}`)).toContainText('Deletion awaiting approval');
    await w.getByTestId('branch-filter-archived').click();
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('Deleted');
    await w.getByTestId('branch-filter-current').click();

    // sort
    await w.getByTestId('branch-sort').selectOption('createdAt,desc');
    await expect(rows(page).first()).toHaveAttribute('data-testid', `branch-row-${OSHAKATI}`);

    // no results is its own state, with a way back
    await search.fill('zzzz');
    await expect(w.getByTestId('branch-no-results')).toBeVisible();
    await w.getByRole('button', { name: 'Clear search and filters' }).click();
    await expect(rows(page)).toHaveCount(20);
  });

  test('maker files a new branch: validation on the field, server refusal on the field, then four-eyes blocks self-approval', async ({ page }) => {
    await openWorkbench(page, ID, { tier: 'md', cells: MANAGER });
    await md(page).locator('[data-action="branch.create"]').click();
    const d = dialog(page);
    await expect(d.getByRole('heading', { name: 'New branch' })).toBeVisible();

    // on blur, then on submit
    await d.getByTestId('branch-name').focus();
    await d.getByTestId('branch-name').blur();
    await expect(d.getByText('Enter a branch name of at least 2 characters')).toBeVisible();
    await d.getByTestId('branch-name').fill('W');
    await d.getByTestId('branch-submit').click();
    await expect(d.getByText('Enter a branch name of at least 2 characters')).toBeVisible();

    await pastCooldown(page);
    await d.getByTestId('branch-name').fill('Walvis Bay');
    await d.getByTestId('branch-submit').click();
    await expect(d.getByText('A branch with this name already exists.')).toBeVisible();

    await pastCooldown(page);
    await d.getByTestId('branch-name').fill('  Ongwediva ');
    await d.getByTestId('branch-submit').click();
    await expect(md(page).getByText(/“Ongwediva” sent for approval \(ref [0-9A-F]{8}\)/)).toBeVisible();

    // the drawer now shows the filed branch: awaiting approval, and the maker cannot decide it
    await expect(d.getByRole('heading', { name: 'Ongwediva', exact: true })).toBeVisible();
    await expect(d.getByTestId('branch-pending')).toContainText('Add the branch Ongwediva');
    await expect(d.getByTestId('branch-pending-blocked')).toHaveText('You submitted this — another person must decide.');
    await expect(d.locator('[data-action="branch.approve"]')).toHaveCount(0);
    await expect(d.getByTestId('branch-change-blocked')).toHaveText('This branch is waiting for its first approval.');

    await page.keyboard.press('Escape');
    await md(page).getByTestId('branch-filter-awaiting').click();
    await expect(rows(page).filter({ hasText: 'Ongwediva' })).toContainText('Awaiting approval');
  });

  test('checker approves a new branch — it becomes active with the checker recorded', async ({ page }) => {
    await openRow(page, OSHAKATI);
    const d = dialog(page);
    await expect(d.getByTestId('branch-pending')).toContainText('Requested by');
    await expect(d.getByTestId('branch-pending')).toContainText('Johannes Kapuka');
    await d.locator('[data-action="branch.approve"]').click();
    await d.getByTestId('branch-decision-confirm').click();
    await expect(md(page).getByText('“Oshakati” approved — the branch is now active.')).toBeVisible();
    await expect(d.getByTestId('branch-pending')).toHaveCount(0);
    await expect(d.getByTestId('branch-details')).toContainText('Active');
    await expect(d.getByTestId('branch-details')).toContainText('Anna Shikongo');
    await expect(md(page).getByTestId(`branch-row-${OSHAKATI}`)).toContainText('Anna Shikongo');
  });

  test('checker rejects a deletion — a reason is required and the branch stays active', async ({ page }) => {
    await openRow(page, KEET);
    const d = dialog(page);
    await expect(d.getByTestId('branch-pending-diff')).toContainText('Delete the branch Keetmanshoop');
    await d.locator('[data-action="branch.reject"]').click();
    await expect(d.getByTestId('branch-decision-confirm')).toBeDisabled();
    await d.getByTestId('branch-decision-reason').focus();
    await d.getByTestId('branch-decision-reason').blur();
    await expect(d.getByText('A rejection needs a reason')).toBeVisible();
    await d.getByTestId('branch-decision-reason').fill('Branch still serves the south region');
    await d.getByTestId('branch-decision-confirm').click();
    await expect(md(page).getByText('Rejected — Maria Nghipondoka will see your reason.')).toBeVisible();
    await expect(d.getByTestId('branch-pending')).toHaveCount(0);
    await expect(md(page).getByTestId(`branch-row-${KEET}`)).toContainText('Active');
  });

  test('maker renames a branch — the old name stays until approved; own request is not decidable', async ({ page }) => {
    await openRow(page, KATIMA);
    const d = dialog(page);
    await d.locator('[data-action="branch.edit"]').click();
    await expect(d.getByRole('heading', { name: 'Rename branch' })).toBeVisible();
    await expect(d.getByTestId('branch-submit')).toBeDisabled(); // unchanged name
    await d.getByTestId('branch-name').fill('Katima Mulilo Town');
    await d.getByTestId('branch-submit').click();
    await expect(md(page).getByText(/Rename to “Katima Mulilo Town” sent for approval/)).toBeVisible();
    await expect(d.getByRole('heading', { name: 'Katima Mulilo', exact: true })).toBeVisible();
    await expect(d.getByTestId('branch-pending-diff')).toContainText('Rename Katima Mulilo → Katima Mulilo Town');
    await expect(d.getByTestId('branch-pending-blocked')).toHaveText('You submitted this — another person must decide.');
    await expect(d.locator('[data-action="branch.edit"]')).toBeDisabled();
    await expect(d.getByTestId('branch-change-blocked')).toHaveText('A change to this branch is already awaiting approval.');
  });

  test('maker deletes a branch after confirming; a branch still in use is refused with the server text', async ({ page }) => {
    await openRow(page, KATIMA);
    const d = dialog(page);
    await d.locator('[data-action="branch.delete"]').click();
    const confirm = d.getByRole('alertdialog', { name: 'Delete Katima Mulilo?' });
    await expect(confirm).toBeVisible();
    await confirm.getByRole('button', { name: 'Send deletion for approval' }).click();
    await expect(md(page).getByText(/Deletion of “Katima Mulilo” sent for approval/)).toBeVisible();
    await expect(d.getByTestId('branch-pending-diff')).toContainText('Delete the branch Katima Mulilo');
    await page.keyboard.press('Escape');

    // Windhoek Head Office has roles assigned → 409 BRANCH_IN_USE
    await md(page).getByTestId('branch-search').fill('windhoek');
    await md(page).getByTestId('branch-row-BR-0001').click();
    await d.locator('[data-action="branch.delete"]').click();
    await d.getByRole('button', { name: 'Send deletion for approval' }).click();
    await expect(d.getByText('3 roles are assigned to this branch — move them to another branch first.')).toBeVisible();
  });

  test('viewer (role.view only) can read everything but change nothing', async ({ page }) => {
    await openRow(page, KATIMA, ['role.view']);
    await expect(md(page).locator('[data-action="branch.create"]')).toHaveCount(0);
    await expect(md(page).getByText(/View only — adding or changing branches needs the role.manage permission/)).toBeVisible();
    const d = dialog(page);
    await expect(d.getByTestId('branch-details')).toContainText('Anna Shikongo');
    await expect(d.locator('[data-action]')).toHaveCount(0);
    await expect(d.getByText(/View only — changing branches needs the role.manage permission/)).toBeVisible();
  });

  test('four-eyes refusal is shown, not hidden, for a request the viewer filed', async ({ page }) => {
    await openRow(page, SWAKOP);
    const d = dialog(page);
    await expect(d.getByTestId('branch-pending-blocked')).toHaveText('You submitted this — another person must decide.');
    await expect(d.locator('[data-action="branch.approve"]')).toHaveCount(0);
    await expect(d.locator('[data-action="branch.reject"]')).toHaveCount(0);
  });
});

test.describe(`${ID} · portal (BFF session)`, () => {
  test.skip(!LANES.includes('portal'), 'portal lane not selected');

  test('renders on its real screen for a real session', async ({ browser }) => {
    const stage = stageOf(ID);
    const s = await portalSession(browser, stage);
    const stubbed = await stubPlannedOps(s.page, stage, fixtures as Record<string, Partial<Record<string, unknown>>>);
    if (stubbed.length) console.log(`stage-1 stubs (NEW ops from fixtures): ${stubbed.join(', ')}`);
    await openScreen(s, stage);
    await expect(s.page.getByText(/not registered yet/i), 'widget registered with core').toHaveCount(0);
    await expect(s.page.getByRole('heading', { name: 'Branches' })).toBeVisible();
    await expect(s.page.getByTestId('branch-search')).toBeVisible();
    await s.context.close();
  });
});
