/**
 * SessionProvider — the react-refresh-safe component surface of the session system.
 * All types/loaders/hooks live in session-core.ts.
 *
 * Hydration chain (once per portal load):
 *   GET /bff/session → user summary (or false)
 *   GET /api/me/permissions → held permission keys (through gateway → core RBAC)
 *   GET /api/tenants/{tid} → tenant subType for org-portal eligibility
 *   GET /bff/act-as → live "sudo into a tenant" session (or none)
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { ApiError } from './api';
import { SessionCtx, NO_SESSION } from './session-core';
import { exitActAs as endActAsSession, fetchActAs, fetchWho, loadTenant, loadPermissions, portalEligible, startActAs as beginActAs } from './session-core';
import type { ActAsView, SessionCtl, SessionState } from './session-core';
import { onSessionAuthed, stopUnreadPolling } from './push-core';
import type { PortalConfig } from '../../portal-config';

export function SessionProvider({ portal, children }: { portal: PortalConfig; children: ReactNode }) {
  const [state, setState] = useState<SessionState>(NO_SESSION);
  const [eligible, setEligible] = useState<boolean | undefined>(undefined);
  const [authedOnce, setAuthedOnce] = useState(false);

  const refresh = useCallback(async () => {
    const user = await fetchWho();
    if (!user) {
      setState({ status: 'anon', user: null, permissions: [], tenant: null, actAs: null });
      setEligible(undefined);
      return;
    }
    try {
      const [permissions, tenant, actAs] = await Promise.all([
        loadPermissions(),
        user.tenantType !== 'PLATFORM' ? loadTenant(user.tenantId) : Promise.resolve(null),
        fetchActAs(),
      ]);
      setState({ status: 'authed', user, permissions, tenant, actAs });
      setEligible(portalEligible(portal, user, tenant));
    } catch (e) {
      if (e instanceof ApiError && e.code === 'AUTH_REQUIRED') {
        // IdP token expired (15 min) — bounce to login, not "no access"
        setState({ status: 'anon', user: null, permissions: [], tenant: null, actAs: null });
        setEligible(undefined);
        return;
      }
      throw e;
    }
  }, [portal]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // F-12: on the FIRST authed hydration of this page load, start the unread badge poll and
  // (best-effort, deferred) enable Web Push for this device. Cleared on session loss.
  useEffect(() => {
    if (state.status === 'authed') {
      if (!authedOnce) {
        setAuthedOnce(true);
        onSessionAuthed();
      }
    } else if (state.status !== 'loading' && authedOnce) {
      setAuthedOnce(false);
      stopUnreadPolling();
    }
  }, [state.status, authedOnce]);

  const value = useMemo<SessionCtl>(
    () => ({
      ...state,
      eligible,
      refresh,
      async startActAs(tenantId: string): Promise<ActAsView> {
        const view = await beginActAs(tenantId); // server-side: permission + strictly-below + audit
        await refresh(); // banner + capped permission set become visible everywhere
        return view;
      },
      async exitActAs(): Promise<void> {
        await endActAsSession();
        await refresh(); // back to the person's own tenant context
      },
    }),
    [state, eligible, refresh],
  );
  return <SessionCtx.Provider value={value}>{children}</SessionCtx.Provider>;
}
