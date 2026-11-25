/**
 * Core of the browser session — ported from SME `BrowserSessionService` + portal auth.
 * The browser sees ONLY a user summary + permission keys from `/api/me/permissions`; the
 * IdP token lives server-side behind the BFF session (FAPI2 UI mandate). Non-component
 * surface (types, loaders, hook): session.tsx owns the react-refresh-safe provider.
 *
 * Hydration chain (once per portal load):
 *   GET /bff/session → user summary (or false)
 *   GET /api/me/permissions → held permission keys (through gateway → core RBAC)
 *   GET /api/tenants/{tid} → tenant subType for org-portal eligibility
 */
import { createContext, useContext } from 'react';
import { apiDelete, apiGet, apiPost, ApiError } from './api';
import type { PortalConfig } from '../../portal-config';

export interface SessionUser {
  userId: string;
  username: string;
  fullName: string;
  tenantId: string;
  tenantName: string | null;
  tenantType: string | null;
  roles: string[];
}

/** One step in the home › … › target chain shown in the act-as banner. */
export interface ActAsPathStep {
  tenantId: string | null;
  name: string | null;
}

/** Projection of core's ActAsView (ActAsService) via GET/POST /bff/act-as. */
export interface ActAsView {
  sessionId: string;
  personName: string | null;
  homeTenantName: string | null;
  targetTenantId: string | null;
  targetTenantName: string | null;
  path: ActAsPathStep[];
  expiresAt: string | null;
  idleUntil: string | null;
}

export interface TenantSummary {
  id: string;
  subType: string | null;
  status: string;
  type: string;
  legalName: string | null;
  parentTenantId: string | null;
  depth: number;
  hierarchyPath: string;
}

export interface SessionState {
  status: 'loading' | 'anon' | 'authed';
  user: SessionUser | null;
  permissions: string[];
  tenant: TenantSummary | null;
  /** Live act-as session ("sudo into a tenant") or null — plans/act-as-tenant-plan.md. */
  actAs: ActAsView | null;
}

export const NO_SESSION: SessionState = { status: 'loading', user: null, permissions: [], tenant: null, actAs: null };

/** How long this session may sit idle, and when it ends regardless (from /bff/session). */
export interface SessionLimits {
  idleTimeoutSeconds: number;
  /** ISO-8601; the BFF ends the session here whatever the activity */
  sessionEndsAt: string | null;
}

let limits: SessionLimits | null = null;
/** The limits the last /bff/session answer carried (null while signed out). */
export function sessionLimits(): SessionLimits | null {
  return limits;
}

/** GET /bff/session (BffSessionController). */
export async function fetchWho(): Promise<SessionUser | null> {
  try {
    const r = await apiGet<{ authenticated: boolean; user: SessionUser | null; idleTimeoutSeconds?: number | null; sessionEndsAt?: string | null }>('/bff/session');
    limits = r.authenticated && r.idleTimeoutSeconds ? { idleTimeoutSeconds: r.idleTimeoutSeconds, sessionEndsAt: r.sessionEndsAt ?? null } : null;
    return r.authenticated ? r.user : null;
  } catch {
    return null;
  }
}

/** Org-portal eligibility: the session's tenant subType must match the portal. */
export async function loadTenant(tenantId: string): Promise<TenantSummary | null> {
  try {
    return await apiGet<TenantSummary>(`/api/tenants/${tenantId}`);
  } catch {
    return null;
  }
}

/** Current act-as session: {active, session} or null when none (GET /bff/act-as). */
export async function fetchActAs(): Promise<ActAsView | null> {
  try {
    const r = await apiGet<{ active: boolean; session: ActAsView | null }>('/bff/act-as');
    return r?.active ? (r.session ?? null) : null;
  } catch {
    return null;
  }
}

/** POST /bff/act-as — start sudo; core enforces tenant.act_as (home), strictly-below, audit. */
export async function startActAs(tenantId: string): Promise<ActAsView> {
  const r = await apiPost<{ active: boolean; session: ActAsView | null }>('/bff/act-as', { tenantId });
  if (!r?.active || !r.session) {
    throw new ApiError({ status: 0, title: 'Act as failed', detail: 'no act-as session was opened' });
  }
  return r.session;
}

/** DELETE /bff/act-as — exit sudo; idempotent: an already-expired row is still an exit. */
export async function exitActAs(): Promise<void> {
  try {
    await apiDelete('/bff/act-as');
  } catch (e) {
    if (!(e instanceof ApiError && e.status === 401)) throw e;
  }
}

export async function loadPermissions(): Promise<string[]> {
  try {
    const r = await apiGet<{ tenantId: string; permissions: string[] }>('/api/me/permissions');
    return r.permissions;
  } catch (e) {
    // Only a real denial means "no permissions". Anything else (rate limit, 5xx, network) is an
    // error: showing a signed-in person "No access" for a hiccup is wrong (staging 2026-11-25).
    if (e instanceof ApiError && e.status === 403) return [];
    throw e; // AUTH_REQUIRED → bounce to login; others → the caller's error path
  }
}

export async function doLogin(username: string, password: string): Promise<SessionUser> {
  await apiPost('/bff/auth/login', { username, password });
  const user = await fetchWho();
  if (!user) throw new ApiError({ status: 0, title: 'Login failed', detail: 'session could not be established' });
  return user;
}

/** Top-level form post so the browser follows the session teardown. */
export interface LogoutOptions {
  /** the portal's base path ('/admin'); its sign-in page is `${basePath}/login` */
  basePath?: string;
  /** the portal's per-portal localStorage key — cleared (FAPI 2.0 logout trio) */
  storageKey?: string;
  /** why: 'idle' | 'expired' | undefined (the person pressed Sign out) */
  reason?: 'idle' | 'expired';
}

/**
 * Logout trio (FAPI 2.0): the BFF drops the session and revokes its refresh token at the IdP,
 * this portal's local state is cleared, and the browser lands on the portal's sign-in page.
 * The BFF call is skipped when the session is already gone (reason 'expired').
 */
export async function doLogout(opts: LogoutOptions = {}): Promise<void> {
  try {
    if (opts.reason !== 'expired') await apiPost('/bff/auth/logout');
  } catch {
    // the session may already be gone — still leave the page signed out
  } finally {
    try {
      if (opts.storageKey) {
        for (const k of Object.keys(window.localStorage)) {
          if (k === opts.storageKey || k.startsWith(`${opts.storageKey}.`)) window.localStorage.removeItem(k);
        }
      }
    } catch {
      // storage unavailable (private mode) — nothing to clear
    }
    limits = null;
    const base = (opts.basePath ?? '').replace(/\/$/, '');
    const q = opts.reason === 'idle' ? '?signed_out=idle' : opts.reason === 'expired' ? '?error=session_expired' : '';
    window.location.href = `${base}/login${q}`;
  }
}

/**
 * Eligibility of ONE hydrated session against ONE portal identity. A tenant's sub-type picks its
 * portal; a tenant row with no sub-type (legacy) is admitted only by a portal that lists its
 * TYPE in `admitsTypes` (PSP → manufacturer). Anything else fails closed. This is a routing
 * decision only — core re-checks every permission on every call.
 */
export function portalEligible(portal: PortalConfig, user: SessionUser | null, tenant: TenantSummary | null): boolean {
  if (!user) return false;
  if (portal.tenantSubType === 'PLATFORM') return user.tenantType === 'PLATFORM';
  if (!tenant) return false;
  if (tenant.subType) return tenant.subType === portal.tenantSubType;
  return (portal.admitsTypes ?? []).includes(tenant.type);
}

export interface SessionCtl extends SessionState {
  eligible: boolean | undefined; // undefined while loading tenant detail
  refresh: () => Promise<void>;
  /** Start acting as a tenant strictly below home (C1); refreshes the session on success. */
  startActAs: (tenantId: string) => Promise<ActAsView>;
  /** Exit the live act-as session (C3); refreshes the session on success. */
  exitActAs: () => Promise<void>;
}

export const SessionCtx = createContext<SessionCtl>({
  ...NO_SESSION,
  eligible: undefined,
  refresh: async () => {},
  startActAs: async () => {
    throw new Error('no SessionProvider — cannot act as a tenant');
  },
  exitActAs: async () => {},
});

export function useSession(): SessionCtl {
  return useContext(SessionCtx);
}

/** OR-semantics permission check against held keys (SME AccessService.can). */
export function can(permissions: string[], anyOf: string[]): boolean {
  return anyOf.some((k) => permissions.includes(k));
}
