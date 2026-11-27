/**
 * org.branches fixture server — plays core's branch API + approvals for the widget workbench:
 * server-side search / status filter / sort / paging; four-eyes on every write (202 + pending
 * approval, nothing applies before the decision); per-caller `canDecide` / `blockedReason`;
 * 403 MAKER_CANNOT_APPROVE · CHECKER_NOT_ENABLED; 409 STALE_WRITE · BRANCH_NAME_TAKEN ·
 * BRANCH_CHANGE_PENDING · BRANCH_IN_USE. The caller is always FIXTURE_ME (a checker); the
 * store is in memory and shared by every frame of the workbench page.
 */
import { ApiError } from '../../../web-core/api';
import type { FixtureRequest, FixtureSet } from '../../core/ports';
import { normaliseName } from './model';
import type { BlockedReason, BranchChange, BranchOperation, BranchPage, BranchRow, PendingChange, StatusFilter } from './model';

export const FIXTURE_ME = { id: '00000000-0000-0000-0000-0000000002d1', name: 'Anna Shikongo', isChecker: true };
const JOHANNES = { id: '00000000-0000-0000-0000-0000000002d2', name: 'Johannes Kapuka' };
const MARIA = { id: '00000000-0000-0000-0000-0000000002d3', name: 'Maria Nghipondoka' };
const PETER = { id: '00000000-0000-0000-0000-0000000002d4', name: 'Peter Amutenya' };

const VIEW = ['role.view', 'role.manage'];
const MANAGE = 'role.manage';

/** a branch with roles assigned — core refuses to delete it (409 BRANCH_IN_USE) */
export const IN_USE_BRANCH_ID = '00000000-0000-0000-0000-00000000b001';

interface Person { id: string; name: string }
/** internal pending change: the caller-specific verdict is computed per request */
type StoredPending = Omit<PendingChange, 'canDecide' | 'blockedReason'>;
type Row = Omit<BranchRow, 'pending'> & { pending: StoredPending | null };

const day = 86_400_000;
const BASE = Date.parse('2026-11-20T08:30:00Z');
const at = (daysAgo: number, minutes = 0) => new Date(BASE - daysAgo * day + minutes * 60_000).toISOString();
/** fake, obviously-fixture UUIDs: last group = 6 zeros + 2-char prefix + 4-digit number */
const uuid = (prefix: string, n: number) => `00000000-0000-0000-0000-000000${prefix}${String(n).padStart(4, '0')}`;

let seq = 0;
let STORE: Row[] = [];

function active(n: number, name: string, maker: Person, checker: Person, daysAgo: number): Row {
  return {
    id: uuid('0b', n), code: `BR-${String(n).padStart(4, '0')}`, name, status: 'ACTIVE', version: 2,
    makerId: maker.id, makerName: maker.name, createdAt: at(daysAgo),
    checkerId: checker.id, checkerName: checker.name, decidedAt: at(daysAgo, 95), decisionReason: null,
    updatedAt: at(daysAgo, 95), pending: null,
  };
}

function pend(row: Row, operation: BranchOperation, by: Person, daysAgo: number, proposedName: string | null): Row {
  row.pending = {
    approvalId: uuid('0a', Number(row.code.slice(3))), operation, proposedName,
    requestedById: by.id, requestedByName: by.name, requestedAt: at(daysAgo, 30),
  };
  return row;
}

const EXTRA_TOWNS = [
  'Gobabis', 'Grootfontein', 'Henties Bay', 'Karibib', 'Mariental', 'Okahandja', 'Omaruru',
  'Ondangwa', 'Opuwo', 'Otjiwarongo', 'Outjo', 'Rehoboth', 'Tsumeb', 'Usakos',
  'Arandis', 'Divundu', 'Kamanjab', 'Nkurenkuru',
];

function seed(): Row[] {
  const rows: Row[] = [
    active(1, 'Windhoek Head Office', JOHANNES, FIXTURE_ME, 210),
    active(2, 'Walvis Bay', MARIA, PETER, 160),
    // filed by Johannes, awaiting its first approval → the signed-in checker may decide it
    pend({ ...active(3, 'Oshakati', JOHANNES, PETER, 2), status: 'PENDING', version: 1, checkerId: null, checkerName: null, decidedAt: null },
      'BRANCH_CREATE', JOHANNES, 2, 'Oshakati'),
    // a rename the signed-in user filed herself → OWN_REQUEST, no decision controls
    pend(active(4, 'Swakopmund', MARIA, JOHANNES, 120), 'BRANCH_UPDATE', FIXTURE_ME, 1, 'Swakopmund Town Centre'),
    // a deletion filed by Maria → decidable
    pend(active(5, 'Keetmanshoop', PETER, MARIA, 95), 'BRANCH_DELETE', MARIA, 0, null),
    {
      ...active(6, 'Rundu Service Point', JOHANNES, PETER, 40), status: 'REJECTED', version: 2,
      decisionReason: 'Duplicate of the Rundu branch that is already being set up.',
    },
    { ...active(7, 'Lüderitz', MARIA, JOHANNES, 300), status: 'ARCHIVED', version: 4, updatedAt: at(12) },
    active(8, 'Katima Mulilo', PETER, FIXTURE_ME, 60),
  ];
  rows[0].id = IN_USE_BRANCH_ID;
  EXTRA_TOWNS.forEach((town, i) => rows.push(active(9 + i, town, i % 2 ? MARIA : JOHANNES, i % 3 ? PETER : FIXTURE_ME, 20 + i * 7)));
  seq = rows.length;
  return rows;
}

/** Back to the seeded store (tests; the workbench keeps its state across frames). */
export function resetBranchFixtures(): void {
  STORE = seed();
}
resetBranchFixtures();

/* ---------------------------------------------------------------- helpers */
const problem = (status: number, code: string, detail: string) => new ApiError({ status, code, title: code, detail });

function need(req: FixtureRequest, keys: string[]) {
  if (!keys.some((k) => req.permissions.includes(k))) {
    throw problem(403, 'PERMISSION_DENIED', `Requires ${keys.join(' or ')} on this organization.`);
  }
}

function find(id: string | undefined): Row {
  const r = STORE.find((b) => b.id === id);
  if (!r) throw problem(404, 'BRANCH_NOT_FOUND', 'This branch does not exist in this organization.');
  return r;
}

function verdict(p: StoredPending, req: FixtureRequest): { canDecide: boolean; blockedReason: BlockedReason | null } {
  if (!req.permissions.includes(MANAGE)) return { canDecide: false, blockedReason: 'NO_PERMISSION' };
  if (p.requestedById === FIXTURE_ME.id) return { canDecide: false, blockedReason: 'OWN_REQUEST' };
  if (!FIXTURE_ME.isChecker) return { canDecide: false, blockedReason: 'NOT_CHECKER' };
  return { canDecide: true, blockedReason: null };
}

function view(r: Row, req: FixtureRequest): BranchRow {
  return { ...r, pending: r.pending ? { ...r.pending, ...verdict(r.pending, req) } : null };
}

const matches = (r: Row, status: StatusFilter): boolean => {
  switch (status) {
    case 'CURRENT': return r.status === 'PENDING' || r.status === 'ACTIVE';
    case 'AWAITING': return r.pending !== null;
    default: return r.status === status;
  }
};

/* ------------------------------------------------------------------ reads */
function list(req: FixtureRequest): BranchPage {
  need(req, VIEW);
  const p = req.params;
  const status = (['CURRENT', 'AWAITING', 'ACTIVE', 'REJECTED', 'ARCHIVED'] as const).find((s) => s === p.status) ?? 'CURRENT';
  const needle = (p.q ?? '').trim().toLocaleLowerCase();
  const [field, dir] = (p.sort ?? 'name,asc').split(',');
  const sign = dir === 'desc' ? -1 : 1;
  const rows = STORE
    .filter((r) => matches(r, status))
    .filter((r) => !needle || r.name.toLocaleLowerCase().includes(needle) || r.code.toLocaleLowerCase().includes(needle)
      || (r.pending?.proposedName ?? '').toLocaleLowerCase().includes(needle))
    .sort((a, b) => sign * (field === 'createdAt' ? a.createdAt.localeCompare(b.createdAt) : a.name.localeCompare(b.name)));
  const size = Math.min(200, Math.max(1, Number.parseInt(p.size ?? '20', 10) || 20));
  const page = Math.max(0, Number.parseInt(p.page ?? '0', 10) || 0);
  const items = rows.slice(page * size, page * size + size).map((r) => view(r, req));
  return {
    items, page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size),
    hasNext: (page + 1) * size < rows.length, sort: `${field},${dir ?? 'asc'}`,
  };
}

function one(req: FixtureRequest): BranchRow {
  need(req, VIEW);
  return view(find(req.params.branchId), req);
}

/* ----------------------------------------------------------------- writes */
function nameTaken(name: string, exceptId: string | null): boolean {
  const n = name.toLocaleLowerCase();
  return STORE.some((r) => r.id !== exceptId && (r.status === 'ACTIVE' || r.status === 'PENDING')
    && (r.name.toLocaleLowerCase() === n || (r.pending?.proposedName ?? '').toLocaleLowerCase() === n));
}

function readName(body: unknown): string {
  const name = normaliseName(String((body as { name?: unknown } | null)?.name ?? ''));
  if (name.length < 2 || name.length > 80) throw problem(422, 'BRANCH_NAME_INVALID', 'A branch name is 2–80 characters.');
  return name;
}

function openChange(r: Row, operation: BranchOperation, proposedName: string | null): BranchChange {
  const approvalId = uuid('0a', 5000 + ++seq);
  r.pending = {
    approvalId, operation, proposedName, requestedById: FIXTURE_ME.id, requestedByName: FIXTURE_ME.name,
    requestedAt: new Date().toISOString(),
  };
  r.version += 1;
  r.updatedAt = r.pending.requestedAt;
  return { pendingApprovalId: approvalId, operationType: operation, branchId: r.id, status: 'PENDING_CHECKER' };
}

function changeable(r: Row, expectedVersion: number | undefined) {
  if (expectedVersion !== r.version) {
    throw problem(409, 'STALE_WRITE', 'Someone changed this branch since you opened it. Reload to see the latest.');
  }
  if (r.pending) throw problem(409, 'BRANCH_CHANGE_PENDING', 'This branch already has a change awaiting approval.');
  if (r.status !== 'ACTIVE') throw problem(409, 'BRANCH_STATE', 'Only active branches can be changed.');
}

function create(req: FixtureRequest): BranchChange {
  need(req, [MANAGE]);
  const name = readName(req.body);
  if (nameTaken(name, null)) throw problem(409, 'BRANCH_NAME_TAKEN', `A branch named “${name}” already exists.`);
  const n = ++seq;
  const now = new Date().toISOString();
  const row: Row = {
    id: uuid('0b', n), code: `BR-${String(n).padStart(4, '0')}`, name, status: 'PENDING', version: 0,
    makerId: FIXTURE_ME.id, makerName: FIXTURE_ME.name, createdAt: now,
    checkerId: null, checkerName: null, decidedAt: null, decisionReason: null, updatedAt: now, pending: null,
  };
  STORE.push(row);
  return openChange(row, 'BRANCH_CREATE', name);
}

function update(req: FixtureRequest): BranchChange {
  need(req, [MANAGE]);
  const r = find(req.params.branchId);
  changeable(r, (req.body as { expectedVersion?: number } | null)?.expectedVersion);
  const name = readName(req.body);
  if (name === r.name) throw problem(422, 'BRANCH_NAME_UNCHANGED', 'The new name is the same as the current one.');
  if (nameTaken(name, r.id)) throw problem(409, 'BRANCH_NAME_TAKEN', `A branch named “${name}” already exists.`);
  return openChange(r, 'BRANCH_UPDATE', name);
}

function remove(req: FixtureRequest): BranchChange {
  need(req, [MANAGE]);
  const r = find(req.params.branchId);
  changeable(r, Number(req.params.expectedVersion));
  if (r.id === IN_USE_BRANCH_ID) {
    throw problem(409, 'BRANCH_IN_USE', '3 roles are assigned to this branch — move them to another branch first.');
  }
  return openChange(r, 'BRANCH_DELETE', null);
}

/* -------------------------------------------------------------- decisions */
function decide(approve: boolean) {
  return (req: FixtureRequest): null => {
    const r = STORE.find((b) => b.pending?.approvalId === req.params.id);
    if (!r?.pending) throw problem(409, 'APPROVAL_NOT_PENDING', 'This request was already decided.');
    const p = r.pending;
    if (p.requestedById === FIXTURE_ME.id) throw problem(403, 'MAKER_CANNOT_APPROVE', 'Maker cannot decide their own request.');
    if (!req.permissions.includes(MANAGE)) throw problem(403, 'PERMISSION_DENIED', 'Requires role.manage on this organization.');
    if (!FIXTURE_ME.isChecker) throw problem(403, 'CHECKER_NOT_ENABLED', 'Only designated checkers may decide.');
    const reason = ((req.body as { reason?: string | null } | null)?.reason ?? '').trim();
    if (!approve && !reason) throw problem(422, 'REASON_REQUIRED', 'A rejection needs a reason.');
    const now = new Date().toISOString();
    if (p.operation === 'BRANCH_CREATE') {
      Object.assign(r, approve
        ? { status: 'ACTIVE', checkerId: FIXTURE_ME.id, checkerName: FIXTURE_ME.name, decidedAt: now }
        : { status: 'REJECTED', checkerId: FIXTURE_ME.id, checkerName: FIXTURE_ME.name, decidedAt: now, decisionReason: reason });
    } else if (approve && p.operation === 'BRANCH_UPDATE') {
      r.name = p.proposedName ?? r.name;
    } else if (approve && p.operation === 'BRANCH_DELETE') {
      r.status = 'ARCHIVED';
    }
    r.pending = null;
    r.version += 1;
    r.updatedAt = now;
    return null;
  };
}

export const BRANCH_FIXTURES: FixtureSet = {
  'org.branches.list': {
    default: list,
    empty: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false, sort: 'name,asc' } satisfies BranchPage,
    error: null,
  },
  'org.branches.get': { default: one, error: null },
  'org.branches.create': { default: create },
  'org.branches.update': { default: update },
  'org.branches.delete': { default: remove },
  'org.branches.approve': { default: decide(true) },
  'org.branches.reject': { default: decide(false) },
};
