/**
 * Logical sources/commands → requests, per context (SME sources.ts).
 * Paths are BFF-relative: '/api/...' rides BffProxyController → gateway → core.
 * A widget NEVER embeds a URL — the port resolves the logical name.
 */
import type { ApiProblem } from '../../web-core/api';

export interface SourceRequest {
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  path: string;
  /**
   * Allow-listed query parameters: only these names ever leave the browser as
   * `?name=value` (paging/search/sort), so a widget cannot smuggle arbitrary params
   * onto a URL. Path params ({x}) are filled first; empty values are dropped.
   */
  query?: readonly string[];
}

export interface SourceEntry {
  '*': SourceRequest;
  /** optional per-portal overrides, keyed by context portal */
  [portal: string]: SourceRequest;
}

export type DataSources = Record<string, SourceEntry>;

/**
 * FSP data-source map (plans §6.3 widgets). '*' = same call for every portal
 * context; context-specific keys select per-portal variants.
 */
export const SOURCES: DataSources = {
  'kyc.orgs.tree':   { '*': { method: 'GET', path: '/api/tenants/{tenantId}/children' } },
  'kyc.orgs.self':   { '*': { method: 'GET', path: '/api/tenants/{tenantId}' } },
  'users.list':      { '*': { method: 'GET', path: '/api/tenants/{tenantId}/users', query: ['page', 'size', 'q', 'status', 'sort'] } },
  'users.get':       { '*': { method: 'GET', path: '/api/tenants/{tenantId}/users/{personId}' } },
  'users.roles':     { '*': { method: 'GET', path: '/api/tenants/{tenantId}/user-roles' } },
  'kyc.case.get':    { '*': { method: 'GET', path: '/api/tenants/{tenantId}/kyc' } },
  'kyc.tasks.get':   { '*': { method: 'GET', path: '/api/approvals?tenantId={tenantId}' } },
  'permissions.all': { '*': { method: 'GET', path: '/api/permissions' } },
  'audit.page':      { '*': { method: 'GET', path: '/api/tenants/{tenantId}/audit?size=10' } },
  'journal.get':     { '*': { method: 'GET', path: '/api/tenants/{tenantId}/journal-entries?size=10' } },
  'approvals.get':   { '*': { method: 'GET', path: '/api/approvals?tenantId={tenantId}' } },
  'rbac.roles':      { '*': { method: 'GET', path: '/api/permissions' } },
  'overview.me':     { '*': { method: 'GET', path: '/api/me/permissions' } },
  'overview.self':   { '*': { method: 'GET', path: '/api/tenants/{tenantId}' } },
  'ledger.books':    { '*': { method: 'GET', path: '/api/tenants/{tenantId}/books' } },
  'periods.get':     { '*': { method: 'GET', path: '/api/tenants/{tenantId}/books' } },
  // KYC plugin (plans/plugin-platform-plan.md §12.4) — BFF /api/plugins/kyc → gateway /v1/plugins/kyc
  'kyc.cases.page':  { '*': { method: 'GET', path: '/api/plugins/kyc/cases', query: ['page', 'size', 'q', 'status', 'risk', 'kind', 'sort'] } },
  'kyc.case.view':   { '*': { method: 'GET', path: '/api/plugins/kyc/cases/{caseId}' } },
  'kyc.case.history': { '*': { method: 'GET', path: '/api/plugins/kyc/cases/{caseId}/history', query: ['page', 'size'] } },
  'kyc.master.list': { '*': { method: 'GET', path: '/api/plugins/kyc/master-values', query: ['category'] } },
};

/**
 * Per-widget data contributions: a widget folder may ship `data.ts` exporting `sources`,
 * `commands` and `fixtures` (docs/skills/fsp-widget-author/SKILL.md). Names must start
 * with the widget id; built-in entries above always win on a clash.
 */
export interface WidgetDataModule {
  sources?: DataSources;
  commands?: DataSources;
  fixtures?: Record<string, Partial<Record<string, unknown>>>;
}
export const WIDGET_DATA: Record<string, WidgetDataModule> = import.meta.glob('../*/*/data.ts', { eager: true });

function contributed(kind: 'sources' | 'commands'): DataSources {
  const out: DataSources = {};
  for (const m of Object.values(WIDGET_DATA)) Object.assign(out, m[kind] ?? {});
  return out;
}
const SOURCES_ALL: DataSources = { ...contributed('sources'), ...SOURCES };

export function resolveSource(name: string, portal: string): SourceRequest | null {
  const e = SOURCES_ALL[name];
  if (!e) return null;
  return e[portal] ?? e['*'] ?? null;
}

/** Logical writes — resolved like sources but with mutating methods. */
export const COMMANDS: DataSources = {
  'kyc.request-info': { '*': { method: 'POST', path: '/api/tenants/{tenantId}/kyc/request-info' } },
  'approval.approve': { '*': { method: 'POST', path: '/api/approvals/{id}/approve' } },
  'approval.reject':  { '*': { method: 'POST', path: '/api/approvals/{id}/reject' } },
  'user.create':     { '*': { method: 'POST', path: '/api/tenants/{tenantId}/users' } },
  'user.update':     { '*': { method: 'PATCH', path: '/api/tenants/{tenantId}/users/{personId}' } },
  'user.deactivate': { '*': { method: 'POST', path: '/api/tenants/{tenantId}/users/{personId}/deactivate' } },
  'user.activate':   { '*': { method: 'POST', path: '/api/tenants/{tenantId}/users/{personId}/activate' } },
  'user.remove':     { '*': { method: 'DELETE', path: '/api/tenants/{tenantId}/users/{personId}?expectedVersion={expectedVersion}' } },
  'user.activation.resend': { '*': { method: 'POST', path: '/api/tenants/{tenantId}/users/{personId}/activation' } },
  'user.password.reset.request': { '*': { method: 'POST', path: '/api/tenants/{tenantId}/users/{personId}/password-reset-request' } },
  'journal.submit': { '*': { method: 'POST', path: '/api/tenants/{tenantId}/journal-entries/{entryId}/submit-for-approval' } },
  // KYC plugin writes — every one carries an Idempotency-Key (web-core/api) and is never auto-retried
  'kyc.case.create': { '*': { method: 'POST', path: '/api/plugins/kyc/cases' } },
  'kyc.case.update': { '*': { method: 'PATCH', path: '/api/plugins/kyc/cases/{caseId}' } },
  'kyc.case.submit': { '*': { method: 'POST', path: '/api/plugins/kyc/cases/{caseId}/submit' } },
  'kyc.case.decide': { '*': { method: 'POST', path: '/api/plugins/kyc/cases/{caseId}/decisions' } },
};

const COMMANDS_ALL: DataSources = { ...contributed('commands'), ...COMMANDS };

export function resolveCommand(name: string, portal: string): SourceRequest | null {
  const e = COMMANDS_ALL[name];
  if (!e) return null;
  return e[portal] ?? e['*'] ?? null;
}

export function fillPath(p: string, params: Record<string, string>): string {
  return p.replace(/\{(\w+)\}/g, (_, k) => {
    const v = params[k];
    if (!v) throw new Error(`source path needs param ${k}`);
    return encodeURIComponent(v);
  });
}

/** Path params filled, then the allow-listed query appended (drops empties, never others). */
export function buildPath(req: SourceRequest, params: Record<string, string>): string {
  const path = fillPath(req.path, params);
  const qs = new URLSearchParams();
  for (const k of req.query ?? []) {
    const v = params[k];
    if (v !== undefined && v !== '') qs.set(k, v);
  }
  const q = qs.toString();
  return q ? `${path}${path.includes('?') ? '&' : '?'}${q}` : path;
}

export interface PortResult<T> { state: 'ok'; data: T }
export type PortOutcome<T> = PortResult<T> | { state: 'forbidden' } | { state: 'empty' };

export type { ApiProblem };
