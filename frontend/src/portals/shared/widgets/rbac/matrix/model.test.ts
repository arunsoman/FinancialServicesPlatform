import { describe, expect, it } from 'vitest';
import { MENU_ITEMS } from '../../../web-core/menu';
import { signedLayoutSchema } from '../../../../signed/signed-layout';
import type { SignedLayout } from '../../../../signed/signed-layout';
import { getWidget } from '../../registry';
import {
  CELLS_PER_ROW, NOTHING_GRANTABLE, activeKey, buildMatrix, cellDiff, checkState, columnCell, roleDetailsSchema, roleKeyOf, rowCells,
  toggleCell, toggleRows,
} from './model';
import type { Grantable, Grants, PermissionDef } from './model';

/** The live core catalogue (permissions table) — key, component. */
const LIVE: Array<[string, string]> = [
  ['audit.view', 'audit'], ['idp.manage', 'identity'], ['je.approve', 'journal'], ['je.create', 'journal'], ['je.view', 'journal'],
  ['bank.account.approve', 'kyc'], ['bank.account.create', 'kyc'], ['kyc.approve', 'kyc'], ['kyc.reject', 'kyc'], ['kyc.review', 'kyc'],
  ['screening.run', 'kyc'], ['bank.account.view', 'ledger'], ['gl.account.approve', 'ledger'], ['gl.account.create', 'ledger'],
  ['gl.account.view', 'ledger'], ['period.close', 'ledger'], ['period.open', 'ledger'], ['notification.view', 'notify'],
  ['org.activate', 'org'], ['org.approve_child', 'org'], ['org.create', 'org'], ['org.onboard_child', 'org'],
  ['platform.bootstrap', 'platform'], ['plugin.install', 'platform'], ['plugin.manage', 'platform'], ['plugin.register', 'platform'],
  ['role.manage', 'rbac'], ['role.view', 'rbac'], ['user.manage', 'rbac'], ['tenant.act_as', 'tenant'],
  ['ui.layout.manage', 'ui'], ['ui.widget.register', 'ui'],
];
const defs: PermissionDef[] = LIVE.map(([key, component]) => ({ key, component, title: key, risk: 'READ' }));

/** A PUBLISHED portal layout (what core signs): its menus/screens are the ONLY source of matrix rows. */
function published(over: Partial<SignedLayout> = {}): SignedLayout {
  const approved = (id: string) => ({ id, version: getWidget(id)!.version, bundle: 'b', bundleHash: 'sha256:x', requires: [] });
  return signedLayoutSchema.parse({
    portal: 'admin', schemaVersion: 1, layoutVersion: 'l7', publishedAt: '2026-11-22T00:00:00Z',
    menus: [
      { id: 'access', label: 'Access control', screens: [
        { id: 'roles', label: 'Roles', placedWidgets: [{ id: 'p1', widgetId: 'rbac.matrix', span: {} }] },
        { id: 'people', label: 'People', placedWidgets: [{ id: 'p2', widgetId: 'users.organization', span: {} }] },
      ] },
      { id: 'books', label: 'Books', screens: [
        { id: 'je', label: 'Journal', placedWidgets: [
          { id: 'p3', widgetId: 'journal.entry', span: {} }, { id: 'p4', widgetId: 'audit.trail', span: {} }] },
        // placed but NOT server-approved for this portal → contributes nothing (fail closed, like the sidebar)
        { id: 'ghost', label: 'Ghost', placedWidgets: [{ id: 'p5', widgetId: 'approvals.queue', span: {} }] },
      ] },
    ],
    widgets: ['rbac.matrix', 'users.organization', 'journal.entry', 'audit.trail'].map(approved),
    ...over,
  });
}
const rows = (l: SignedLayout) => buildMatrix(l, defs, getWidget).groups.flatMap((g) => g.rows);

const none: Grants = { keys: new Set(), cells: new Set() };
const everything: Grantable = { keys: new Set(LIVE.map(([k]) => k)), menu: { full: true, cells: new Set() } };

describe('rbac.matrix · rows are exactly the PUBLISHED menu (never the coded registry)', () => {
  it('one group per published menu, one row per submenu, in published order, linked like the sidebar', () => {
    const m = buildMatrix(published(), defs, getWidget);
    expect(m.groups.map((g) => [g.label, g.rows.map((r) => [r.label, r.link])])).toEqual([
      ['Access control', [['Roles', '/p/access/roles'], ['People', '/p/access/people']]],
      ['Books', [['Journal', '/p/books/je'], ['Ghost', '/p/books/ghost']]],
    ]);
  });
  it('labels nobody coded: the matrix follows a rename in the portal builder', () => {
    const coded = new Set(MENU_ITEMS.map((i) => i.label));
    for (const r of rows(published())) expect(coded.has(r.label), r.label).toBe(false);
  });
  it('every row has the same six cells (5 actions + Active), keyed by the published ids', () => {
    const role = rows(published()).find((r) => r.label === 'Roles')!;
    expect(rowCells(role)).toEqual([
      'access/roles:CREATE', 'access/roles:VIEW', 'access/roles:UPDATE', 'access/roles:DELETE', 'access/roles:AUTHORIZE', 'access/roles:ACTIVE',
    ]);
    expect(CELLS_PER_ROW).toBe(6);
  });
  it('an action box also carries the keys of that family the submenu’s widgets need', () => {
    const role = rows(published()).find((r) => r.label === 'Roles')!;
    expect(role.gate).toEqual(['role.manage', 'role.view']);
    expect(role.keys.view.map((c) => c.key)).toEqual(['role.view']);
    expect(role.keys.update.map((c) => c.key)).toEqual(['role.manage']);
    const je = rows(published()).find((r) => r.label === 'Journal')!;
    expect(je.keys.create.map((k) => k.key)).toContain('je.create');
    expect(je.keys.authorize.map((k) => k.key)).toContain('je.approve');
  });
  it('a widget the server did not approve (or of another major), or one that reads nothing, carries no keys', () => {
    const ghost = rows(published()).find((r) => r.label === 'Ghost')!;
    expect(Object.values(ghost.keys).flat()).toEqual([]);
    const l = published({ widgets: published().widgets.map((w) => (w.id === 'rbac.matrix' ? { ...w, version: '9.0.0' } : w)) });
    expect(Object.values(rows(l).find((r) => r.label === 'Roles')!.keys).flat()).toEqual([]);
    const ph = published({
      menus: [{ id: 'user-management', label: 'User Management', screens: [
        { id: 'customers', label: 'Customer', placedWidgets: [{ id: 'p1', widgetId: 'menu.placeholder', span: {} }] }] }],
      widgets: [{ id: 'menu.placeholder', version: getWidget('menu.placeholder')!.version, bundle: 'b', bundleHash: 'h', requires: [] }],
    });
    const c = rows(ph)[0];
    expect(Object.values(c.keys).flat()).toEqual([]);
    expect(rowCells(c)).toContain('user-management/customers:DELETE'); // the cells are there all the same
  });
});

describe('rbac.matrix · boxes grant menu cells (+ the screen’s keys)', () => {
  const m = buildMatrix(published(), defs, getWidget);
  const row = (label: string) => m.groups.flatMap((g) => g.rows).find((r) => r.label === label)!;

  it('ticking View grants the cell and the view-family keys; unticking removes both', () => {
    const on = toggleCell(row('Roles'), 'view', none, everything);
    expect([...on.cells]).toEqual(['access/roles:VIEW']);
    expect([...on.keys]).toEqual(['role.view']);
    const off = toggleCell(row('Roles'), 'view', on, everything);
    expect(off.cells.size + off.keys.size).toBe(0);
  });
  it('Active grants the cell plus the least gate key so the screen renders; unticking keeps the keys', () => {
    expect(activeKey(row('Roles').gate, everything.keys)).toBe('role.view');
    const on = toggleCell(row('Roles'), 'active', none, everything);
    expect([...on.cells]).toEqual(['access/roles:ACTIVE']);
    expect([...on.keys]).toEqual(['role.view']);
    const off = toggleCell(row('Roles'), 'active', on, everything);
    expect([...off.cells]).toEqual([]);
    expect([...off.keys]).toEqual(['role.view']);
  });
  it('Delete exists on every row even when no widget has a delete key (cell only)', () => {
    const on = toggleCell(row('People'), 'delete', none, everything);
    expect([...on.cells]).toEqual(['access/people:DELETE']);
  });
  it('never grants a cell or key the editor does not hold (no escalation)', () => {
    const limited: Grantable = { keys: new Set(['role.view']), menu: { full: false, cells: new Set(['access/roles:VIEW']) } };
    expect([...toggleCell(row('Roles'), 'update', none, limited).cells]).toEqual([]);
    const v = toggleCell(row('Roles'), 'view', none, limited);
    expect([...v.cells]).toEqual(['access/roles:VIEW']);
    expect(toggleCell(row('Roles'), 'view', none, NOTHING_GRANTABLE)).toBe(none);
  });
  it('row / group select-all: all on, then all off; locked cells never move', () => {
    const all = toggleRows([row('Roles')], none, everything);
    expect(checkState(rowCells(row('Roles')), all.cells)).toBe('all');
    expect(toggleRows([row('Roles')], all, everything).cells.size).toBe(0);
    const g = m.groups[0].rows;
    const grp = toggleRows(g, none, everything);
    expect(grp.cells.size).toBe(g.length * CELLS_PER_ROW);
    const limited: Grantable = { keys: new Set(), menu: { full: false, cells: new Set([columnCell(row('Roles'), 'active')]) } };
    expect([...toggleRows(g, none, limited).cells]).toEqual(['access/roles:ACTIVE']);
  });
  it('reports what changed against the role', () => {
    expect(cellDiff(['a/b:VIEW', 'a/b:ACTIVE'], new Set(['a/b:VIEW', 'a/b:CREATE']))).toEqual({ added: ['a/b:CREATE'], removed: ['a/b:ACTIVE'] });
  });
});

describe('rbac.matrix · Role Details', () => {
  it('derives the role key from its name', () => {
    expect(roleKeyOf('Teller Supervisor')).toBe('TELLER_SUPERVISOR');
    expect(roleKeyOf('  KYC — approver  ')).toBe('KYC_APPROVER');
    expect(roleKeyOf('2nd line')).toBe('R_2ND_LINE');
  });
  it('requires branch and department only when the organization has some', () => {
    const v = { name: 'Teller Supervisor', description: 'Supervises tellers', branchId: '', departmentId: '' };
    expect(roleDetailsSchema({ branch: false, department: false }).safeParse(v).success).toBe(true);
    const r = roleDetailsSchema({ branch: true, department: true }).safeParse(v);
    expect(r.success).toBe(false);
    expect(r.error!.issues.map((i) => i.path[0]).sort()).toEqual(['branchId', 'departmentId']);
    expect(roleDetailsSchema({ branch: false, department: false }).safeParse({ ...v, description: ' ' }).success).toBe(false);
  });
});
