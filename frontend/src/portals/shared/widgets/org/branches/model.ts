/**
 * org.branches — wire types, list query, validation and every pure helper (no React).
 *
 * Every branch write is four-eyes: the maker files it, core opens a pending approval, and a
 * DIFFERENT person with the checker flag decides it (core approvals, person_id rule). Nothing
 * applies before that decision. The server computes `pending.canDecide` / `blockedReason` for
 * the caller — the UI renders that verdict and never re-derives it.
 */
import { format } from 'date-fns';
import { z } from 'zod';

/* ------------------------------------------------------------------ wire */
/** PENDING: filed, awaiting its first approval · REJECTED: creation refused · ARCHIVED: deleted. */
export type BranchStatus = 'PENDING' | 'ACTIVE' | 'REJECTED' | 'ARCHIVED';
export type BranchOperation = 'BRANCH_CREATE' | 'BRANCH_UPDATE' | 'BRANCH_DELETE';
export type BlockedReason = 'OWN_REQUEST' | 'NOT_CHECKER' | 'NO_PERMISSION';

/** The one change waiting for a checker (a branch carries at most one). */
export interface PendingChange {
  approvalId: string;
  operation: BranchOperation;
  /** CREATE: the filed name · UPDATE: the proposed new name · DELETE: null */
  proposedName: string | null;
  requestedById: string;
  requestedByName: string;
  requestedAt: string;
  /** server verdict for THIS caller: permission + checker flag + maker ≠ checker */
  canDecide: boolean;
  blockedReason: BlockedReason | null;
}

/** GET /api/tenants/{tenantId}/branches item · GET …/branches/{branchId} */
export interface BranchRow {
  id: string;
  /** server-assigned on filing, never changes */
  code: string;
  name: string;
  status: BranchStatus;
  version: number;
  makerId: string;
  makerName: string;
  createdAt: string;
  /** the checker who decided the creation (approved → ACTIVE, or rejected) */
  checkerId: string | null;
  checkerName: string | null;
  decidedAt: string | null;
  /** the checker's reason when the creation was rejected */
  decisionReason: string | null;
  updatedAt: string;
  pending: PendingChange | null;
}

/** The platform collection envelope (AGENTS §2.7), same as OrgUnitDtos.OrgUnitPage. */
export interface BranchPage {
  items: BranchRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  sort: string | null;
}

/** 202 answer of create / update / delete: the approval it opened — the branch is unchanged until decided. */
export interface BranchChange {
  pendingApprovalId: string;
  operationType: BranchOperation;
  branchId: string;
  status: 'PENDING_CHECKER';
}

/* ------------------------------------------------------------ list query */
/** CURRENT = PENDING + ACTIVE · AWAITING = any branch with a change waiting for a checker. */
export type StatusFilter = 'CURRENT' | 'AWAITING' | 'ACTIVE' | 'REJECTED' | 'ARCHIVED';

export const STATUS_FILTERS: ReadonlyArray<{ id: StatusFilter; label: string }> = [
  { id: 'CURRENT', label: 'All' },
  { id: 'AWAITING', label: 'Awaiting approval' },
  { id: 'ACTIVE', label: 'Active' },
  { id: 'REJECTED', label: 'Rejected' },
  { id: 'ARCHIVED', label: 'Deleted' },
];
export const DEFAULT_STATUS: StatusFilter = 'CURRENT';

/** Sort choices — each one on core's sort safelist. */
export const SORT_OPTIONS = [
  { id: 'name,asc', label: 'Name A–Z' },
  { id: 'name,desc', label: 'Name Z–A' },
  { id: 'createdAt,desc', label: 'Newest first' },
  { id: 'createdAt,asc', label: 'Oldest first' },
] as const;
export type SortId = (typeof SORT_OPTIONS)[number]['id'];
export const DEFAULT_SORT: SortId = 'name,asc';

export const PAGE_SIZES = [20, 50, 100] as const;
export const DEFAULT_PAGE_SIZE = 20;
export const SEARCH_MIN_LENGTH = 2;
export const SEARCH_DEBOUNCE_MS = 300;

export interface BranchListQuery {
  page: number;
  size: number;
  q: string;
  status: StatusFilter;
  sort: SortId;
}

const STATUS_IDS: readonly string[] = STATUS_FILTERS.map((f) => f.id);
const SORT_IDS: readonly string[] = SORT_OPTIONS.map((o) => o.id);

/** URL → list query; anything unknown falls back to its default (ui-std §10.6: sanitise, never error). */
export function readListQuery(get: (key: string) => string | null): BranchListQuery {
  const status = get('status') ?? '';
  const sort = get('sort') ?? '';
  const size = Number.parseInt(get('size') ?? '', 10);
  return {
    page: Math.max(0, Number.parseInt(get('page') ?? '0', 10) || 0),
    size: (PAGE_SIZES as readonly number[]).includes(size) ? size : DEFAULT_PAGE_SIZE,
    q: (get('q') ?? '').trim(),
    status: STATUS_IDS.includes(status) ? (status as StatusFilter) : DEFAULT_STATUS,
    sort: SORT_IDS.includes(sort) ? (sort as SortId) : DEFAULT_SORT,
  };
}

/** The search text that is actually sent: below the minimum length nothing is sent (ui-std §10.5.2). */
export function searchTerm(raw: string): string {
  const t = raw.trim();
  return t.length >= SEARCH_MIN_LENGTH ? t : '';
}

/** `org.branches.list` params — search, filter, sort and paging all run in core. */
export function listParams(orgId: string, q: BranchListQuery): Record<string, string> {
  const out: Record<string, string> = {
    tenantId: orgId, page: String(q.page), size: String(q.size), status: q.status, sort: q.sort,
  };
  const text = searchTerm(q.q);
  if (text) out.q = text;
  return out;
}

/* ------------------------------------------------------------ validation */
export const NAME_MAX = 80;

/** Collapses inner whitespace — "Walvis   Bay " and "Walvis Bay" are the same branch. */
export const normaliseName = (raw: string): string => raw.trim().replace(/\s+/g, ' ');

/** Mirrors core's branch-name rule so most mistakes are caught on blur; core re-validates. */
export const branchFormSchema = z.object({
  name: z
    .string()
    .transform(normaliseName)
    .pipe(
      z
        .string()
        .min(2, 'Enter a branch name of at least 2 characters')
        .max(NAME_MAX, `Keep the name under ${NAME_MAX} characters`)
        .regex(/^[\p{L}\p{N}][\p{L}\p{N} .,'&()/-]*$/u, 'Use letters, digits, spaces and . , \' & ( ) / -'),
    ),
});
export type BranchFormValues = z.infer<typeof branchFormSchema>;

/** The first problem with a name, or null when it is fine. */
export function nameError(raw: string): string | null {
  const r = branchFormSchema.safeParse({ name: raw });
  return r.success ? null : (r.error.issues[0]?.message ?? 'Check the branch name');
}

/** Editing to the same name (ignoring spacing and case) is not a change. */
export function isSameName(a: string, b: string): boolean {
  return normaliseName(a).toLocaleLowerCase() === normaliseName(b).toLocaleLowerCase();
}

/* --------------------------------------------------------- request bodies */
export function createBody(v: BranchFormValues): { name: string } {
  return { name: normaliseName(v.name) };
}

export function updateBody(branch: BranchRow, v: BranchFormValues): { name: string; expectedVersion: number } {
  return { name: normaliseName(v.name), expectedVersion: branch.version };
}

export function deleteParams(orgId: string, branch: BranchRow): Record<string, string> {
  return { tenantId: orgId, branchId: branch.id, expectedVersion: String(branch.version) };
}

/** Approve: reason optional (null when blank) · reject: reason required (checked by the caller). */
export function decisionBody(reason: string): { reason: string | null } {
  const r = reason.trim();
  return { reason: r === '' ? null : r };
}

/* --------------------------------------------------------------- display */
export type Tone = 'ok' | 'warn' | 'bad' | 'neutral';

export interface StatusView {
  label: string;
  tone: Tone;
}

/** What a row's status chip says; a pending rename / deletion is shown on an active branch. */
export function statusView(b: Pick<BranchRow, 'status' | 'pending'>): StatusView {
  if (b.pending?.operation === 'BRANCH_UPDATE') return { label: 'Rename awaiting approval', tone: 'warn' };
  if (b.pending?.operation === 'BRANCH_DELETE') return { label: 'Deletion awaiting approval', tone: 'warn' };
  switch (b.status) {
    case 'PENDING': return { label: 'Awaiting approval', tone: 'warn' };
    case 'ACTIVE': return { label: 'Active', tone: 'ok' };
    case 'REJECTED': return { label: 'Rejected', tone: 'bad' };
    case 'ARCHIVED': return { label: 'Deleted', tone: 'neutral' };
  }
}

/** The "Approved by" column. */
export function approvedByText(b: Pick<BranchRow, 'status' | 'checkerName'>): string {
  if (b.status === 'PENDING') return 'Awaiting checker';
  if (b.status === 'REJECTED') return b.checkerName ? `Rejected by ${b.checkerName}` : 'Rejected';
  return b.checkerName ?? '—';
}

/** "23 Nov 2026, 10:40" in the viewer's time zone. */
export function dateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : format(d, 'd MMM yyyy, HH:mm');
}

export const OPERATION_LABEL: Record<BranchOperation, string> = {
  BRANCH_CREATE: 'New branch',
  BRANCH_UPDATE: 'Rename',
  BRANCH_DELETE: 'Deletion',
};

/** Short, readable approval reference for the maker's "sent for approval" state. */
export const shortRef = (id: string): string => id.replace(/-/g, '').slice(-8).toUpperCase();

/** The maker's confirmation after filing: nothing has changed yet, and who must act next. */
export function filedNotice(operation: BranchOperation, name: string, approvalId: string | null | undefined): string {
  const ref = approvalId ? ` (ref ${shortRef(approvalId)})` : '';
  switch (operation) {
    case 'BRANCH_CREATE': return `“${name}” sent for approval${ref} — a different person must approve it before it can be used.`;
    case 'BRANCH_UPDATE': return `Rename to “${name}” sent for approval${ref} — the branch keeps its current name until then.`;
    case 'BRANCH_DELETE': return `Deletion of “${name}” sent for approval${ref} — the branch stays active until then.`;
  }
}

/** The checker's confirmation after deciding. */
export function decidedNotice(b: Pick<BranchRow, 'name'>, p: Pick<PendingChange, 'operation' | 'proposedName' | 'requestedByName'>, approve: boolean): string {
  if (!approve) return `Rejected — ${p.requestedByName} will see your reason.`;
  switch (p.operation) {
    case 'BRANCH_CREATE': return `“${p.proposedName ?? b.name}” approved — the branch is now active.`;
    case 'BRANCH_UPDATE': return `Rename approved — the branch is now “${p.proposedName ?? b.name}”.`;
    case 'BRANCH_DELETE': return `Deletion approved — “${b.name}” is deleted.`;
  }
}

/** Why Edit / Delete is unavailable — null when allowed. Core re-checks every call. */
export function changeBlocker(b: Pick<BranchRow, 'status' | 'pending'>, canManage: boolean): string | null {
  if (!canManage) return 'Changing branches needs the role.manage permission.';
  if (b.status === 'PENDING') return 'This branch is waiting for its first approval.';
  if (b.pending) return 'A change to this branch is already awaiting approval.';
  if (b.status !== 'ACTIVE') return 'Only active branches can be changed.';
  return null;
}

/** The four-eyes refusals, in the wording ui-std §5.2 mandates — shown, never hidden. */
export const MAKER_BLOCKED = 'You submitted this — another person must decide.';
export const NOT_CHECKER = 'You aren’t enabled as a checker.';

export function blockedText(reason: BlockedReason | null): string | null {
  switch (reason) {
    case 'OWN_REQUEST': return MAKER_BLOCKED;
    case 'NOT_CHECKER': return NOT_CHECKER;
    case 'NO_PERMISSION': return 'Deciding this needs the role.manage permission.';
    default: return null;
  }
}

/** Server problem codes that belong on the name field rather than the form. */
export const NAME_FIELD_CODES: ReadonlySet<string> = new Set(['BRANCH_NAME_TAKEN', 'BRANCH_NAME_INVALID']);

/** A failed call → what the user reads. Unknown codes fall back to the server's own detail. */
export function problemText(p: { code: string; message: string }): string {
  switch (p.code) {
    case 'MAKER_CANNOT_APPROVE': return MAKER_BLOCKED;
    case 'CHECKER_NOT_ENABLED': return NOT_CHECKER;
    case 'BRANCH_NAME_TAKEN': return 'A branch with this name already exists.';
    case 'BRANCH_CHANGE_PENDING': return 'This branch already has a change awaiting approval.';
    case 'BRANCH_IN_USE': return p.message || 'Roles are still assigned to this branch — move them to another branch first.';
    case 'STALE_WRITE': return 'Someone changed this branch since you opened it. Reload to see the latest.';
    default: return p.message || 'Something went wrong — try again.';
  }
}

/* ------------------------------------------------------------- query keys */
export const branchKeys = {
  org: (orgId: string) => ['org', 'branches', orgId] as const,
  list: (orgId: string, q: BranchListQuery) => ['org', 'branches', orgId, 'list', q] as const,
  one: (orgId: string, id: string) => ['org', 'branches', orgId, 'one', id] as const,
};
