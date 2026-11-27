/** rbac.matrix — wire types + hooks. Every write is STAGED (202) for a checker; never optimistic. */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { cellsOf, requiresRows } from '../../core/manifest';
import type { WidgetDataPortLike, WidgetDef } from '../../core/manifest';
import { menuCell, placedDef, screenLink } from '../../../../signed/signed-layout';
import type { MenuAccess, MenuAction } from '../../../../signed/signed-layout';
import { parseMenuAccess } from '../../../../signed/menu-access';
import type { SignedLayout } from '../../../../signed/signed-layout';
import { loadMenus } from '../../../../signed/delivery';
import { deliveryKeyForTenant, portalLabel } from '../../../../portal-config';
import { toProblem } from '../../kit/problem';

export interface RoleView {
  id: string;
  tenantId: string;
  key: string;
  name: string;
  scope: string;
  status: 'ACTIVE' | 'PENDING_CREATE';
  permissionKeys: string[];
  version: number;
  assignedCount: number;
  protectedRole: boolean;
  pendingApprovalId: string | null;
  pendingOperation: string | null;
  /** what the role is for (Role Description) */
  description: string | null;
  branchId: string | null;
  branchName: string | null;
  departmentId: string | null;
  departmentName: string | null;
  /** the portal its menu cells are keyed to (null = it holds none) */
  portal: string | null;
  /** Permissions Matrix cells, "<menu>/<submenu>:<ACTION>" */
  menuCells: string[];
  /** built-in roles hold the whole menu implicitly */
  fullMenu: boolean;
}

/** A branch or department (core OrgUnitController). */
export interface OrgUnit {
  id: string;
  code: string;
  name: string;
  status: string;
}

export interface OrgUnitPage {
  items: OrgUnit[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  sort: string;
}

export interface PermissionDef {
  key: string;
  title: string;
  risk: 'READ' | 'WRITE' | 'FOUR_EYES';
  component: string;
}

export interface RoleChange {
  pendingApprovalId: string;
  operationType: string;
  roleId: string;
  status: string;
}

/** "Teller Supervisor" → "TELLER_SUPERVISOR": the role's unique key, derived from its name. */
export function roleKeyOf(name: string): string {
  let k = name.trim().normalize('NFD').replace(/[\u0300-\u036f]/g, '').toUpperCase()
    .replace(/[^A-Z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  if (k && !/^[A-Z]/.test(k)) k = `R_${k}`;
  return k.slice(0, 48);
}

/**
 * Role Details (Add Role form). Branch / Department are required whenever the organization has
 * any (`unitsRequired`) — the same rule core enforces (BRANCH_REQUIRED / DEPARTMENT_REQUIRED).
 */
export function roleDetailsSchema(unitsRequired: { branch: boolean; department: boolean }) {
  return z.object({
    name: z.string().trim().min(2, 'Name the role').max(80, 'Keep it under 80 characters')
      .refine((v) => /^[A-Z][A-Z0-9_]{1,47}$/.test(roleKeyOf(v)), 'Use at least 2 letters or digits, starting with a letter'),
    description: z.string().trim().min(2, 'Describe the role’s responsibilities').max(500, 'Keep it under 500 characters'),
    branchId: unitsRequired.branch ? z.string().min(1, 'Choose a branch') : z.string(),
    departmentId: unitsRequired.department ? z.string().min(1, 'Choose a department') : z.string(),
  });
}
export type RoleDetailsValues = z.infer<ReturnType<typeof roleDetailsSchema>>;

export const rbacKeys = {
  all: (orgId: string) => ['rbac', orgId] as const,
  roles: (orgId: string) => ['rbac', orgId, 'roles'] as const,
  catalog: ['rbac', 'catalog'] as const,
};

export function useRoles(port: WidgetDataPortLike, orgId: string) {
  return useQuery({
    queryKey: rbacKeys.roles(orgId),
    queryFn: () => port.request<RoleView[]>('rbac.matrix.roles', { tenantId: orgId }),
    enabled: orgId !== '',
  });
}

/** The organization's ACTIVE branches / departments for the Role Details selects (server-sorted by name). */
export function useOrgUnits(port: WidgetDataPortLike, orgId: string, kind: 'branches' | 'departments') {
  return useQuery({
    queryKey: ['rbac', orgId, kind] as const,
    queryFn: () => port.request<OrgUnitPage>(`rbac.matrix.${kind}`, { tenantId: orgId }),
    enabled: orgId !== '',
    staleTime: 5 * 60_000,
  });
}

/**
 * What the EDITOR holds on the role's portal menu (core /me/menu-access) — only those cells can
 * be granted or revoked (no escalation; core re-checks: ROLE_MENU_NOT_ASSIGNABLE).
 */
export function useEditorMenuAccess(port: WidgetDataPortLike, portal: string | null) {
  return useQuery({
    queryKey: ['rbac-menu-access', portal] as const,
    queryFn: async (): Promise<MenuAccess> => parseMenuAccess(portal!, await port.request<unknown>('rbac.matrix.access', { portal: portal! })),
    enabled: portal !== null,
    staleTime: 60_000,
    retry: 1,
  });
}

export function useCatalog(port: WidgetDataPortLike) {
  return useQuery({
    queryKey: rbacKeys.catalog,
    queryFn: () => port.request<PermissionDef[]>('rbac.matrix.catalog'),
    staleTime: 10 * 60_000,
  });
}

/** GET /api/tenants/{id} — only what picks the org's portal. */
export interface OrgPortalView {
  id: string;
  type: string;
  subType: string | null;
}

/** The role's portal's published menu, as the matrix sees it. */
export type MenuState =
  | { kind: 'loading' }
  | { kind: 'ready'; portal: string; label: string; layout: SignedLayout }
  | { kind: 'missing'; portal: string | null }
  | { kind: 'invalid'; portal: string; reason: string }
  | { kind: 'error'; message: string; retry: () => void };

/**
 * The matrix rows' ONLY source: the published menu of the portal the org's
 * people sign in to (platform org → admin console; a tenant → its sub-type's portal; when acting
 * as a tenant, the TARGET's). No published layout → no rows (never the coded menu).
 */
export function usePublishedMenu(port: WidgetDataPortLike, orgId: string, mode: 'live' | 'fixture'): MenuState {
  const org = useQuery({
    queryKey: ['rbac-menu', orgId, 'org'],
    queryFn: () => port.request<OrgPortalView>('rbac.matrix.org', { tenantId: orgId }),
    enabled: orgId !== '',
    staleTime: 10 * 60_000,
    retry: 0,
  });
  const portal = org.data ? deliveryKeyForTenant(org.data) : null;
  const menu = useQuery({
    queryKey: ['rbac-menu', orgId, 'layout', portal, mode],
    queryFn: () => loadMenus(portal!, () => port.request<unknown>('rbac.matrix.menu', { portal: portal! })),
    enabled: portal !== null,
    staleTime: 60_000,
    retry: 1, // an idempotent read (ui-std §7)
  });

  if (org.isError) return { kind: 'error', message: toProblem(org.error).message, retry: () => void org.refetch() };
  if (org.isPending) return { kind: 'loading' };
  if (portal === null) return { kind: 'missing', portal: null };
  if (menu.isError) return { kind: 'error', message: toProblem(menu.error).message, retry: () => void menu.refetch() };
  if (menu.isPending) return { kind: 'loading' };
  const d = menu.data;
  switch (d.kind) {
    case 'layout': return { kind: 'ready', portal, label: portalLabel(portal), layout: d.layout };
    case 'missing': return { kind: 'missing', portal };
    case 'reauth': return { kind: 'error', message: 'Your session has ended — sign in again.', retry: () => void menu.refetch() };
    case 'invalid': return { kind: 'invalid', portal, reason: d.reason };
  }
}

/** What a role grants: permission keys (API rights) + menu cells (the matrix). */
export interface Grants {
  keys: ReadonlySet<string>;
  cells: ReadonlySet<string>;
}

export interface RoleDetailsBody {
  name: string;
  description: string;
  branchId: string | null;
  departmentId: string | null;
}

export type RoleCommand =
  | { kind: 'create'; key: string; details: RoleDetailsBody; grants: Grants }
  | { kind: 'update'; role: RoleView; details: RoleDetailsBody; grants: Grants }
  | { kind: 'delete'; role: RoleView };

export function useRoleCommand(port: WidgetDataPortLike, orgId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (c: RoleCommand): Promise<RoleChange> => {
      switch (c.kind) {
        case 'create':
          return port.command<RoleChange>('rbac.matrix.create', {
            key: c.key, ...c.details, permissionKeys: [...c.grants.keys].sort(), menuCells: [...c.grants.cells].sort(),
          }, { tenantId: orgId });
        case 'update':
          return port.command<RoleChange>('rbac.matrix.update', {
            ...c.details, permissionKeys: [...c.grants.keys].sort(), menuCells: [...c.grants.cells].sort(),
            expectedVersion: c.role.version,
          }, { tenantId: orgId, roleId: c.role.id });
        case 'delete':
          return port.command<RoleChange>('rbac.matrix.delete', undefined,
            { tenantId: orgId, roleId: c.role.id, expectedVersion: String(c.role.version) });
      }
    },
    retry: 0,
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: rbacKeys.all(orgId) });
      void qc.invalidateQueries({ queryKey: ['approvals'] });
    },
  });
}

/** Group the catalog by owning component, stable order. */
export function byComponent(defs: PermissionDef[]): Array<[string, PermissionDef[]]> {
  const m = new Map<string, PermissionDef[]>();
  for (const d of [...defs].sort((a, b) => a.key.localeCompare(b.key))) {
    const list = m.get(d.component) ?? [];
    list.push(d);
    m.set(d.component, list);
  }
  return [...m.entries()].sort(([a], [b]) => a.localeCompare(b));
}

/* ───────────────────────────── permission matrix (published submenu × action) ─────────────────────────────
 * Rows are EXACTLY the role's portal's PUBLISHED menu — the signed, verified layout core delivers
 * (the same one that draws that portal's sidebar), never the coded menu registry: one header row
 * per published menu, one row per submenu, in published order.
 *
 * Columns are fixed (Select all · Create · View · Update · Delete · Authorize · Active). Every box
 * is a MENU CELL core stores on the role ("<menu>/<submenu>:<ACTION>"):
 *   - Active = the submenu shows in that portal's sidebar;
 *   - Create/View/Update/Delete/Authorize = what its screen may do.
 * Ticking an action box ALSO grants the permission keys of that action family that the submenu's
 * placed widgets need (e.g. View on Roles → role.view), so the screen actually works; ticking
 * Active also grants the least key that makes the screen render. Unticking removes the cell and
 * that family's keys; it never touches keys no submenu uses (editing a role never strips them).
 * Widgets that read and send nothing (the "Coming soon" placeholder) contribute no keys.
 */
export type MatrixColumn = 'create' | 'view' | 'update' | 'delete' | 'authorize';

export const MATRIX_COLUMNS: readonly MatrixColumn[] = ['create', 'view', 'update', 'delete', 'authorize'];

export const COLUMN_LABEL: Record<MatrixColumn, string> = {
  create: 'Create', view: 'View', update: 'Update', delete: 'Delete', authorize: 'Authorize',
};

const COLUMN_ACTION: Record<MatrixColumn, MenuAction> = {
  create: 'CREATE', view: 'VIEW', update: 'UPDATE', delete: 'DELETE', authorize: 'AUTHORIZE',
};

/** Cells per row: the five action columns + Active. */
export const CELLS_PER_ROW = MATRIX_COLUMNS.length + 1;

const VERB_TO_COLUMN: Record<string, MatrixColumn> = {
  create: 'create', register: 'create', install: 'create', onboard_child: 'create', bootstrap: 'create', upload: 'create',
  view: 'view', review: 'view',
  manage: 'update', activate: 'update', open: 'update', close: 'update', run: 'update', act_as: 'update',
  delete: 'delete', remove: 'delete', archive: 'delete', revoke: 'delete',
  approve: 'authorize', reject: 'authorize', approve_child: 'authorize', authorize: 'authorize',
};

export interface MatrixKey {
  key: string;
  title: string;
  risk: PermissionDef['risk'];
}

export interface MatrixRow {
  menuId: string;
  screenId: string;
  /** the submenu's route (/p/<menu>/<screen>), unique per portal */
  link: string;
  label: string;
  /** permission keys each action box also grants (from the submenu's placed widgets) */
  keys: Record<MatrixColumn, MatrixKey[]>;
  /** the screen's gate — the least of these is granted with Active so the screen renders */
  gate: string[];
}

export interface MatrixGroup {
  /** the published menu's id (unique per layout) */
  id: string;
  /** the published menu's label (the sidebar group header) */
  label: string;
  rows: MatrixRow[];
}

export interface PermissionMatrixModel {
  groups: MatrixGroup[];
}

export function splitKey(key: string): { resource: string; verb: string } {
  const i = key.lastIndexOf('.');
  return i < 0 ? { resource: key, verb: '' } : { resource: key.slice(0, i), verb: key.slice(i + 1) };
}

export function columnOf(key: string): MatrixColumn {
  return VERB_TO_COLUMN[splitKey(key).verb] ?? 'update';
}

/** A widget that reads and sends nothing grants nothing (e.g. the "Coming soon" placeholder). */
function grantsKeys(def: WidgetDef): boolean {
  return (def.sources?.length ?? 0) > 0 || (def.commands?.length ?? 0) > 0;
}

/**
 * Build the matrix from a portal's verified, published layout. `registry` is this build's widget
 * registry — passed in so the model stays pure.
 */
export function buildMatrix(
  layout: SignedLayout,
  defs: readonly PermissionDef[],
  registry: (widgetId: string) => WidgetDef | undefined,
): PermissionMatrixModel {
  const byKey = new Map(defs.map((d) => [d.key, d] as const));
  const known = (ks: Iterable<string>) => [...new Set(ks)].filter((k) => byKey.has(k)).sort();
  const groups: MatrixGroup[] = [];
  for (const menu of layout.menus) {
    const group: MatrixGroup = { id: menu.id, label: menu.label, rows: [] };
    for (const screen of menu.screens) {
      const placed = screen.placedWidgets
        .map((p) => placedDef(layout, p, registry))
        .filter((d): d is WidgetDef => d !== null && grantsKeys(d));
      const keys: Record<MatrixColumn, MatrixKey[]> = { create: [], view: [], update: [], delete: [], authorize: [] };
      for (const k of known(placed.flatMap((d) => cellsOf(d)))) {
        const d = byKey.get(k)!;
        keys[columnOf(k)].push({ key: k, title: d.title, risk: d.risk });
      }
      group.rows.push({
        menuId: menu.id, screenId: screen.id, link: screenLink(menu, screen), label: screen.label, keys,
        gate: known(placed.flatMap((d) => requiresRows(d))),
      });
    }
    groups.push(group);
  }
  return { groups };
}

export type CheckState = 'all' | 'some' | 'none';

export function columnCell(row: MatrixRow, column: MatrixColumn | 'active'): string {
  return menuCell(row.menuId, row.screenId, column === 'active' ? 'ACTIVE' : COLUMN_ACTION[column]);
}

/** Every cell of a row, Active last. */
export function rowCells(row: MatrixRow): string[] {
  return [...MATRIX_COLUMNS.map((c) => columnCell(row, c)), columnCell(row, 'active')];
}

export function checkState(items: readonly string[], selected: ReadonlySet<string>): CheckState {
  const n = items.filter((k) => selected.has(k)).length;
  return n === 0 ? 'none' : n === items.length ? 'all' : 'some';
}

/** What the editor may change: the keys they hold + the menu cells they hold (no escalation). */
export interface Grantable {
  keys: ReadonlySet<string>;
  menu: MenuAccess;
}

export const NOTHING_GRANTABLE: Grantable = { keys: new Set(), menu: { full: false, cells: new Set() } };

export function canGrantCell(g: Grantable, cell: string): boolean {
  return g.menu.full || g.menu.cells.has(cell);
}

/** The one key ticking Active grants: the gate's view-family key if it has one, else its first grantable key. */
export function activeKey(gate: readonly string[], grantable: ReadonlySet<string>): string | null {
  const mine = gate.filter((k) => grantable.has(k));
  return mine.find((k) => columnOf(k) === 'view') ?? mine[0] ?? null;
}

function setCell(row: MatrixRow, column: MatrixColumn | 'active', on: boolean, cur: Grants, g: Grantable): Grants {
  const cell = columnCell(row, column);
  if (!canGrantCell(g, cell)) return cur;
  const keys = new Set(cur.keys);
  const cells = new Set(cur.cells);
  if (on) {
    cells.add(cell);
    if (column === 'active') {
      if (!row.gate.some((k) => keys.has(k))) {
        const k = activeKey(row.gate, g.keys);
        if (k) keys.add(k);
      }
    } else {
      for (const k of row.keys[column]) if (g.keys.has(k.key)) keys.add(k.key);
    }
  } else {
    cells.delete(cell);
    if (column !== 'active') for (const k of row.keys[column]) if (g.keys.has(k.key)) keys.delete(k.key);
  }
  return { keys, cells };
}

/** One box: on ↔ off (locked boxes do nothing). */
export function toggleCell(row: MatrixRow, column: MatrixColumn | 'active', cur: Grants, g: Grantable): Grants {
  return setCell(row, column, !cur.cells.has(columnCell(row, column)), cur, g);
}

/**
 * "Select all" over rows: if every cell the editor may grant is already on, they all go off;
 * otherwise they all go on. Boxes the editor may not grant never change.
 */
export function toggleRows(rows: readonly MatrixRow[], cur: Grants, g: Grantable): Grants {
  const mine = rows.flatMap((r) => rowCells(r).filter((c) => canGrantCell(g, c)));
  const allOn = mine.length > 0 && mine.every((c) => cur.cells.has(c));
  let next = cur;
  for (const r of rows) {
    for (const col of [...MATRIX_COLUMNS, 'active' as const]) next = setCell(r, col, !allOn, next, g);
  }
  return next;
}

/** Cells added / removed against the role as it is. */
export function cellDiff(before: readonly string[], after: ReadonlySet<string>): { added: string[]; removed: string[] } {
  const b = new Set(before);
  return {
    added: [...after].filter((c) => !b.has(c)).sort(),
    removed: [...b].filter((c) => !after.has(c)).sort(),
  };
}
