/**
 * Notification centre — wire types, query keys, hooks and pure helpers (non-component:
 * react-refresh-safe). Everything goes through the same-origin BFF (`/api/...`, FAPI2: no
 * token in the browser). A notification only POINTS at work: core re-checks every permission
 * and four-eyes rule when the person acts, so nothing here is authority.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiGet, apiPost } from '../api';
import { pollUnread } from '../push-core';
import type { QueueRow } from '../../widgets/approvals/queue/model';

export interface NotificationItem {
  id: string;
  type: string;
  opType: string;
  title: string;
  body: string;
  link: string;
  unread: boolean;
  createdAt: string;
  resourceType: string;
  resourceId: string;
  /** decided on the record itself (approve/reject) — can be snoozed, never dismissed */
  actionable: boolean;
  snoozedUntil: string | null;
  resolvedAt: string | null;
}

export interface NotificationPage {
  items: NotificationItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  unread: number;
  pending: number;
}

export type InboxState = 'pending' | 'snoozed';

export const PAGE_SIZE = 20;

export const notificationKeys = {
  all: ['notifications'] as const,
  inbox: (state: InboxState, size: number) => ['notifications', 'inbox', state, size] as const,
  approval: (id: string) => ['notifications', 'approval', id] as const,
};

/** Server-paged inbox (AGENTS §2.7): "Show more" grows `size`, never filters client-side. */
export function useInbox(state: InboxState, size: number, enabled: boolean) {
  return useQuery({
    queryKey: notificationKeys.inbox(state, size),
    queryFn: () => apiGet<NotificationPage>(
      `/api/notifications?state=${state}&page=0&size=${size}&sort=createdAt,desc`),
    enabled,
    retry: 1,
    placeholderData: (prev) => prev,
  });
}

/** The approval a notification points at, with THIS caller's canDecide / blockedReason. */
export function useApproval(id: string | null) {
  return useQuery({
    queryKey: notificationKeys.approval(id ?? ''),
    queryFn: () => apiGet<QueueRow>(`/api/approvals/${encodeURIComponent(id ?? '')}`),
    enabled: id !== null,
    retry: 1,
  });
}

function useInvalidate() {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: notificationKeys.all });
    void pollUnread(); // the bell badge
  };
}

export function useSnooze() {
  const done = useInvalidate();
  return useMutation({
    mutationFn: (v: { id: string; until: Date }) =>
      apiPost<NotificationItem>(`/api/notifications/${encodeURIComponent(v.id)}/snooze`, { until: v.until.toISOString() }),
    retry: 0,
    onSettled: done,
  });
}

export function useWake() {
  const done = useInvalidate();
  return useMutation({
    mutationFn: (id: string) => apiPost<NotificationItem>(`/api/notifications/${encodeURIComponent(id)}/wake`),
    retry: 0,
    onSettled: done,
  });
}

export function useDismiss() {
  const done = useInvalidate();
  return useMutation({
    mutationFn: (id: string) => apiPost<void>(`/api/notifications/${encodeURIComponent(id)}/dismiss`),
    retry: 0,
    onSettled: done,
  });
}

/** Fire-and-forget: opening an item marks it read (cosmetic; failure changes nothing). */
export function markRead(id: string): void {
  void apiPost<void>(`/api/notifications/${encodeURIComponent(id)}/read`).catch(() => undefined);
}

/**
 * Four-eyes decision from the bell (ui-std §7: never optimistic, zero retries). A decision
 * resolves the pointer server-side for every checker, and changes the subject's own screens.
 */
export function useDecideFromNotification(orgId: string) {
  const qc = useQueryClient();
  const done = useInvalidate();
  return useMutation({
    mutationFn: (d: { approvalId: string; approve: boolean; reason: string }) =>
      apiPost<void>(`/api/approvals/${encodeURIComponent(d.approvalId)}/${d.approve ? 'approve' : 'reject'}`,
        { reason: d.reason }),
    retry: 0,
    onSettled: () => {
      done();
      void qc.invalidateQueries({ queryKey: ['approvals'] });
      void qc.invalidateQueries({ queryKey: ['users', 'org', orgId] });
      void qc.invalidateQueries({ queryKey: ['rbac', orgId] });
      void qc.invalidateQueries({ queryKey: ['journal', orgId] });
    },
  });
}

// ---------------- pure helpers ----------------

export interface SnoozeOption {
  id: 'hour' | 'afternoon' | 'tomorrow' | 'nextWeek';
  label: string;
  until: Date;
}

/**
 * Snooze choices relative to `now` (local time): in 1 hour · later today 17:00 (only while
 * that is still > 1 h away) · tomorrow 09:00 · next Monday 09:00.
 */
export function snoozeOptions(now: Date): SnoozeOption[] {
  const at = (days: number, hour: number) => {
    const d = new Date(now);
    d.setDate(d.getDate() + days);
    d.setHours(hour, 0, 0, 0);
    return d;
  };
  const out: SnoozeOption[] = [{ id: 'hour', label: 'In 1 hour', until: new Date(now.getTime() + 3_600_000) }];
  const afternoon = at(0, 17);
  if (afternoon.getTime() - now.getTime() > 3_600_000) out.push({ id: 'afternoon', label: 'Later today (17:00)', until: afternoon });
  out.push({ id: 'tomorrow', label: 'Tomorrow (09:00)', until: at(1, 9) });
  const toMonday = ((8 - now.getDay()) % 7) || 7; // always the NEXT Monday, never today
  out.push({ id: 'nextWeek', label: 'Next week (Mon 09:00)', until: at(toMonday, 9) });
  return out;
}

const AUTO_OPEN_KEY = 'fsp.notifications.autoOpened';

/**
 * "Show me what's waiting when I sign in": open the centre once per signed-in browser session
 * per person, and only when something is pending. Storage can be missing/blocked (private
 * mode) — then it simply opens once per page load.
 */
export function shouldAutoOpen(userId: string | undefined, pending: number | null, store: Pick<Storage, 'getItem' | 'setItem'> | null): boolean {
  if (!userId || pending === null || pending <= 0) return false;
  try {
    if (store?.getItem(AUTO_OPEN_KEY) === userId) return false;
    store?.setItem(AUTO_OPEN_KEY, userId);
  } catch {
    /* storage blocked: fall through to open */
  }
  return true;
}

export function sessionStore(): Pick<Storage, 'getItem' | 'setItem'> | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

/** "5 min ago" / "3 h ago" / "2 d ago" — compact, for the list. */
export function ago(iso: string, now: Date = new Date()): string {
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return '';
  const min = Math.max(0, Math.round((now.getTime() - t) / 60_000));
  if (min < 1) return 'just now';
  if (min < 60) return `${min} min ago`;
  const h = Math.round(min / 60);
  if (h < 24) return `${h} h ago`;
  return `${Math.round(h / 24)} d ago`;
}

/** "until Tue 09:00" for the snoozed tab. */
export function untilLabel(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleString(undefined, { weekday: 'short', hour: '2-digit', minute: '2-digit' });
}
