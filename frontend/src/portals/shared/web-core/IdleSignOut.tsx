/**
 * Signs the person out of the portal after the BFF's idle limit of REAL inactivity (pointer,
 * keys, scroll, touch — any tab of this portal), with a one-minute warning and "Stay signed in".
 * It also answers the page-wide "session ended" signal (api.ts) by going straight to the sign-in
 * page, so widgets never sit there saying "session required".
 *
 * FAPI 2.0: the browser holds no token. Signing out = the logout trio (doLogout): the BFF drops the
 * session and revokes the refresh token at the IdP, this portal's local state is cleared, and the
 * browser lands on `${basePath}/login`. While the person is active, the BFF renews the access
 * token server-side (SessionTokens) — nothing here touches tokens.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import type { PortalConfig } from '../../portal-config';
import { apiGet, SESSION_ENDED_EVENT } from './api';
import { activityKey, idleVerdict } from './idle';
import type { IdleVerdict } from './idle';
import { doLogout, sessionLimits, useSession } from './session-core';

const ACTIVITY_EVENTS = ['pointerdown', 'pointermove', 'keydown', 'wheel', 'touchstart', 'scroll'] as const;
const WRITE_EVERY_MS = 10_000;
const TICK_MS = 1_000;

function readActivity(key: string, fallback: number): number {
  try {
    const v = Number(window.localStorage.getItem(key));
    return Number.isFinite(v) && v > 0 ? Math.max(v, fallback) : fallback;
  } catch {
    return fallback;
  }
}

function writeActivity(key: string, at: number): void {
  try {
    window.localStorage.setItem(key, String(at));
  } catch {
    // storage unavailable: the in-memory time still guards this tab
  }
}

export function IdleSignOut({ portal }: { portal: PortalConfig }): ReactNode {
  const session = useSession();
  const authed = session.status === 'authed';
  const key = activityKey(portal.storageKey);
  const last = useRef(Date.now());
  const lastWrite = useRef(0);
  const leaving = useRef(false);
  const [verdict, setVerdict] = useState<IdleVerdict>({ kind: 'active' });

  const signOut = useCallback((why: 'idle' | 'expired') => {
    if (leaving.current) return;
    leaving.current = true;
    void doLogout({ basePath: portal.basePath, storageKey: portal.storageKey, reason: why });
  }, [portal.basePath, portal.storageKey]);

  const markActive = useCallback(() => {
    const now = Date.now();
    last.current = now;
    if (now - lastWrite.current >= WRITE_EVERY_MS) {
      lastWrite.current = now;
      writeActivity(key, now);
    }
  }, [key]);

  // real user activity (throttled writes, shared across this portal's tabs)
  useEffect(() => {
    if (!authed) return undefined;
    markActive();
    for (const e of ACTIVITY_EVENTS) window.addEventListener(e, markActive, { passive: true, capture: true });
    return () => {
      for (const e of ACTIVITY_EVENTS) window.removeEventListener(e, markActive, { capture: true });
    };
  }, [authed, markActive]);

  // the clock
  useEffect(() => {
    if (!authed) return undefined;
    const t = window.setInterval(() => {
      const limits = sessionLimits();
      if (!limits) return;
      const lastAny = readActivity(key, last.current);
      const endsAt = limits.sessionEndsAt ? Date.parse(limits.sessionEndsAt) : null;
      const v = idleVerdict(Date.now(), lastAny, limits.idleTimeoutSeconds, endsAt);
      if (v.kind === 'sign-out') signOut(v.why === 'expired' ? 'expired' : 'idle');
      setVerdict((cur) => (cur.kind === v.kind && (cur.kind !== 'warn' || (v.kind === 'warn' && cur.secondsLeft === v.secondsLeft)) ? cur : v));
    }, TICK_MS);
    return () => window.clearInterval(t);
  }, [authed, key, signOut]);

  // the BFF says the session is gone (expired, revoked, absolute limit): straight to sign-in
  useEffect(() => {
    const onEnded = () => signOut('expired');
    window.addEventListener(SESSION_ENDED_EVENT, onEnded);
    return () => window.removeEventListener(SESSION_ENDED_EVENT, onEnded);
  }, [signOut]);

  const stay = () => {
    lastWrite.current = 0;
    markActive();
    void apiGet('/bff/session').catch(() => undefined); // a request also keeps the server session alive
    setVerdict({ kind: 'active' });
  };

  if (!authed || verdict.kind !== 'warn') return null;
  const expired = verdict.why === 'expired';
  return (
    <div role="alertdialog" aria-live="assertive" aria-labelledby="idle-title" data-state="idle-warning"
      className="fixed inset-x-0 bottom-4 z-[60] mx-auto w-[min(92vw,440px)] rounded-2xl border border-line bg-panel p-4 shadow-xl">
      <div id="idle-title" className="text-sm font-extrabold">
        {expired ? 'Your session is ending' : 'Are you still there?'}
      </div>
      <p className="text-dim mt-1 text-[13px]">
        {expired
          ? `For security, sessions last a fixed time. You’ll be signed out in ${verdict.secondsLeft} s — sign in again to continue.`
          : `You’ll be signed out in ${verdict.secondsLeft} s because there’s been no activity.`}
      </p>
      <div className="mt-3 flex justify-end gap-2">
        <button type="button" className="fsp-btn fsp-btn-ghost px-3 py-1.5 text-xs" onClick={() => signOut(expired ? 'expired' : 'idle')}>
          Sign out now
        </button>
        {!expired && (
          <button type="button" className="fsp-btn fsp-btn-primary px-3 py-1.5 text-xs" data-action="session.stay" onClick={stay}>
            Stay signed in
          </button>
        )}
      </div>
    </div>
  );
}
