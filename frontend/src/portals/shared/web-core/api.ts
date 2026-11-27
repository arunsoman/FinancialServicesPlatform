/**
 * THE one network path for portal code (SME `WidgetDataPort` idea, FSP BFF lane):
 * every call goes through the same-origin BFF — cookie session, CSRF double-submit,
 * token stays server-side (FAPI2 UI mandate). Never fetch, never cookies:false.
 */
import { holdWhile } from './submit-guard';

/** Fired on window when the BFF says the session is gone (NO_SESSION / SESSION_EXPIRED). */
export const SESSION_ENDED_EVENT = 'fsp:session-ended';

export interface ApiProblem {
  status: number;
  code?: string;
  title: string;
  detail: string;
}

export class ApiError extends Error implements ApiProblem {
  readonly status: number;
  readonly code?: string;
  readonly title: string;
  readonly detail: string;
  readonly problem: ApiProblem;
  constructor(p: ApiProblem) {
    super(`${p.code ?? p.status}: ${p.detail}`);
    this.status = p.status;
    this.code = p.code;
    this.title = p.title;
    this.detail = p.detail;
    this.problem = p;
  }
}

export function readProblem(err: unknown): ApiProblem {
  if (err instanceof ApiError) return { status: err.status, code: err.code, title: err.title, detail: err.message };
  return { status: 0, title: 'Network error', detail: String(err instanceof Error ? err.message : err) };
}

let csrf: { headerName: string; token: string } | null = null;

/** Materializes the CSRF cookie once per session (BffSessionController /bff/csrf). */
export async function ensureCsrf(): Promise<void> {
  if (csrf) return;
  const r = await fetch('/bff/csrf', { credentials: 'same-origin' });
  if (r.ok) csrf = (await r.json()) as { headerName: string; token: string };
}

/** GETs session-cached CSRF token (after ensureCsrf); force = re-read the cookie. */
export async function getCsrf(force = false): Promise<string | null> {
  if (!csrf || force) await ensureCsrf();
  return csrf?.token ?? null;
}

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

interface ApiOpts {
  method?: HttpMethod;
  body?: unknown;
  /** skip the CSRF dance (GET) */
  raw?: boolean;
  /**
   * AGENTS §2e: every mutation carries an Idempotency-Key. Pass the SAME key to retry one
   * logical action; omitted → a fresh UUIDv4 per call.
   */
  idempotencyKey?: string;
  /**
   * Default true for mutations: an identical mutation (method + path + body) already on the
   * wire is JOINED instead of re-sent, so a double-fire never reaches the server twice.
   * Pass false only when two identical concurrent writes are genuinely intended.
   */
  dedupe?: boolean;
}

/** In-flight mutations keyed by method + path + body (submit-guard layer 3). */
const inflightMutations = new Map<string, Promise<unknown>>();

function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

/**
 * Same-origin BFF call. `/api/...` rides BffProxyController → gateway → core;
 * `/bff/...` are the session-control endpoints. 401 → `AuthRequiredError`.
 */
export function api<T>(path: string, opts: ApiOpts = {}): Promise<T> {
  const method = opts.method ?? 'GET';
  if (method === 'GET') return send<T>(path, method, opts);
  const key = opts.dedupe === false
    ? null
    : `${method} ${path} ${opts.body === undefined ? '' : JSON.stringify(opts.body)}`;
  const joined = key ? inflightMutations.get(key) : undefined;
  if (joined) return joined as Promise<T>;
  const p = holdWhile(send<T>(path, method, opts));
  if (key) {
    inflightMutations.set(key, p);
    const clear = () => { if (inflightMutations.get(key) === p) inflightMutations.delete(key); };
    p.then(clear, clear);
  }
  return p;
}

async function send<T>(path: string, method: HttpMethod, opts: ApiOpts): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/problem+json, application/json' };
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET') {
    const token = await getCsrf();
    if (token) headers['X-XSRF-TOKEN'] = token;
    headers['Idempotency-Key'] = opts.idempotencyKey ?? newIdempotencyKey();
  }
  const r = await fetch(path, {
    method,
    credentials: 'same-origin',
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  }).catch(() => {
    throw new ApiError({ status: 0, title: 'Network error', detail: 'BFF unreachable' });
  });
  if (r.status === 401) {
    // The BFF's own "no/ended session" answers end the session for the WHOLE page — the shell
    // sends the person to sign-in instead of every widget showing an error. Other 401s (an
    // act-as row that expired, a plugin hiccup) are the caller's to handle.
    const pd = (await r.json().catch(() => null)) as { code?: string } | null;
    if (pd?.code === 'NO_SESSION' || pd?.code === 'SESSION_EXPIRED') {
      window.dispatchEvent(new CustomEvent(SESSION_ENDED_EVENT, { detail: pd.code }));
    }
    throw new ApiError({ status: 401, code: 'AUTH_REQUIRED', title: 'Not authenticated', detail: 'session required' });
  }
  if (r.status === 403) {
    // ui-std §5.2: a denial names WHAT was denied — keep the server's detail + code when present
    const pd = (await r.json().catch(() => null)) as { code?: string; detail?: string } | null;
    throw new ApiError({
      status: 403,
      code: 'AUTH_FORBIDDEN',
      title: pd?.code ?? 'Forbidden',
      detail: pd?.detail ?? 'the server refused this call',
    });
  }
  if (!r.ok) {
    const pd = (await r.json().catch(() => null)) as
      | { code?: string; title?: string; detail?: string }
      | null;
    throw new ApiError({
      status: r.status,
      code: pd?.code,
      title: pd?.title ?? `HTTP ${r.status}`,
      detail: pd?.detail ?? r.statusText,
    });
  }
  if (r.status === 204) return undefined as T;
  const text = await r.text();
  // Void core handlers (e.g. four-eyes approve/reject) answer 200 with an EMPTY body —
  // r.json() would throw SyntaxError and turn a successful decision into a failed mutation.
  if (!text.length) return undefined as T;
  return JSON.parse(text) as T;
}

export const apiGet = <T,>(path: string) => api<T>(path, { method: 'GET' });

export function apiPost<T>(path: string, body?: unknown) {
  return api<T>(path, { method: 'POST', body });
}

export function apiPut<T>(path: string, body?: unknown) {
  return api<T>(path, { method: 'PUT', body });
}

export function apiPatch<T>(path: string, body?: unknown) {
  return api<T>(path, { method: 'PATCH', body });
}

export function apiDelete<T>(path: string) {
  return api<T>(path, { method: 'DELETE' });
}
