/**
 * Live pending-approvals count for the workbench top bar (SME pending-approvals.ts).
 *
 * Four-eyes visibility: after a maker submits (JE posting, UI layout publish, …), the
 * decision now lives with a DIFFERENT person — this badge shows how many requests are
 * waiting in the tenant's checker queue so the status is never invisible between
 * sign-ins. Polled quietly (never on a timer while signed out), one page capped at 100;
 * deep review stays in the Approvals widget (`/w/approvals.queue`), which renders the
 * real canDecide verdicts.
 */
import { useQuery } from '@tanstack/react-query';
import { ApiError, apiGet } from '../portals/shared/web-core/api';
import { useSession } from '../portals/shared/web-core/session-core';
import { useConnection } from './connection';

/** Same shape the live queue reads (approvals/queue/model.ts QueueRow) — only status is needed. */
interface QueueRow {
  id: string;
  status: string;
}

/** Page cap shared with the queue widget's fetch (size=100). */
const PAGE_SIZE = 100;

/**
 * Count of PENDING_CHECKER approvals in the signed-in user's tenant, polled every 30 s
 * and refreshed on window focus. Disabled signed-out; null while loading / on error —
 * the caller renders "—" instead of a number so the badge never lies.
 */
export function usePendingApprovalCount() {
  const conn = useConnection();
  const session = useSession();
  const tenantId = session.user?.tenantId ?? null;
  return useQuery({
    queryKey: ['approvals', tenantId ?? '', 'PENDING_CHECKER', 'count'],
    queryFn: async () => {
      try {
        const rows = await apiGet<QueueRow[]>(
          `/api/approvals?tenantId=${encodeURIComponent(tenantId ?? '')}&status=PENDING_CHECKER&size=${PAGE_SIZE}`);
        // Array (core returns the page's rows directly); other shapes shouldn't happen.
        return Array.isArray(rows) ? rows.length as number : 0;
      } catch (e) {
        // The server session can expire while the tab sits open — the client-side
        // `conn.status` stays 'connected' and would otherwise poll 401s forever every
        // 30 s. One BFF session refresh flips the connection to signed-out, which
        // disables this query (and the badge) until the user signs back in.
        if (e instanceof ApiError && (e.status === 401 || e.status === 403)) void session.refresh();
        throw e;
      }
    },
    enabled: conn.status === 'connected' && !!tenantId,
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
    retry: 1,
    staleTime: 10_000,
  });
}

/** Badge text: "N" (or "100+" at the page cap), "—" while unknown. */
export function pendingCountText(count: number | undefined): string {
  if (count === undefined) return '—';
 return count >= PAGE_SIZE ? `${PAGE_SIZE}+` : String(count);
}
