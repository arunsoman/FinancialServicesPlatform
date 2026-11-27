/**
 * The two data ports — FSP translation of SME GatewayDataPort/FixtureDataPort.
 * GatewayDataPort: same-origin BFF lane via web-core/api (session cookie + CSRF; the IdP
 * token never reaches the browser — FAPI2). FixtureDataPort: scenario-driven standalone
 * lane for the widget workbench/tests. Both record every call for the network table.
 */
import { api, apiGet, ApiError } from '../../web-core/api';
import type { WidgetDataPortLike } from './manifest';
import { buildPath, resolveSource, resolveCommand } from './sources';
import type { SourceRequest } from './sources';

export interface CallRecord {
  /** per-port sequence number (network table ordering) */
  seq?: number;
  name: string;
  kind: 'source' | 'command';
  /** the resolved request ("GET /api/…") or "fixture" */
  resolved: string;
  status: 'ok' | 'forbidden' | 'empty' | 'error';
  detail?: string;
  params?: Record<string, string>;
  ms?: number;
  /** the answer (fixture / response body) — shown truncated, never logged */
  response?: unknown;
}

type Recorder = (r: CallRecord) => void;

/** Nothing to show: null, [], or an empty page envelope ({ items: [] } — AGENTS §2f collections). */
export function isEmpty(v: unknown): boolean {
  if (v === null || v === undefined) return true;
  if (Array.isArray(v)) return v.length === 0;
  const items = typeof v === 'object' ? (v as { items?: unknown }).items : undefined;
  return Array.isArray(items) && items.length === 0;
}

function statusOf(err: unknown): CallRecord['status'] {
  return err instanceof ApiError && (err.status === 401 || err.status === 403) ? 'forbidden' : 'error';
}

export class GatewayDataPort implements WidgetDataPortLike {
  private seq = 0;

  constructor(
    private readonly context: () => {
      orgId: string | null;
      portal: string;
      params: Record<string, string>;
    },
    private readonly onCall?: Recorder,
  ) {}

  private resolveOne(name: string, portal: string): { kind: 'source' | 'command'; req: SourceRequest } | null {
    const s = resolveSource(name, portal);
    if (s) return { kind: 'source', req: s };
    const c = resolveCommand(name, portal);
    return c ? { kind: 'command', req: c } : null;
  }

  private emit(r: CallRecord) {
    this.onCall?.({ ...r, seq: ++this.seq });
  }

  async request<T>(source: string, params?: Record<string, string>): Promise<T> {
    return this.run<T>(source, 'source', params, undefined);
  }

  async command<T>(command: string, body?: unknown, params?: Record<string, string>): Promise<T> {
    return this.run<T>(command, 'command', params, body);
  }

  private async run<T>(name: string, kind: 'source' | 'command', params: Record<string, string> | undefined, body: unknown): Promise<T> {
    const ctx = this.context();
    const r = this.resolveOne(name, ctx.portal);
    if (!r) {
      const detail = `no mapping for "${name}"`;
      this.emit({ name, kind, resolved: name, status: 'error', detail, params });
      throw new ApiError({ status: 0, title: kind === 'source' ? 'Missing source' : 'Missing command', detail });
    }
    const path = buildPath(r.req, { tenantId: ctx.orgId ?? '', ...ctx.params, ...params });
    const resolved = `${r.req.method} ${path}`;
    const t0 = performance.now();
    try {
      const data = kind === 'source' ? await apiGet<T>(path) : await api<T>(path, { method: r.req.method, body });
      this.emit({
        name, kind, resolved, params, ms: Math.round(performance.now() - t0), response: data,
        status: kind === 'source' && isEmpty(data) ? 'empty' : 'ok',
      });
      return data;
    } catch (err) {
      this.emit({
        name, kind, resolved, params, ms: Math.round(performance.now() - t0), status: statusOf(err),
        detail: err instanceof Error ? err.message : String(err),
      });
      throw err;
    }
  }
}

/** The scenarios a fixture lane can play (fixtures.ts labels them for the workbench). */
export type FixtureScenario = 'default' | 'empty' | 'error' | 'not-ready';

/** What a fixture resolver sees: the call, plus the caller's held cells (as the server would). */
export interface FixtureRequest {
  params: Record<string, string>;
  body: unknown;
  permissions: readonly string[];
  scenario: FixtureScenario;
}

/** A fixture answer: a canned value, or a resolver that plays the server (may throw ApiError). */
export type FixtureAnswer = unknown | ((req: FixtureRequest) => unknown);
export type FixtureSet = Record<string, Partial<Record<FixtureScenario, FixtureAnswer>>>;

/**
 * FixtureDataPort — scenario-driven answers per logical source/command, after an optional
 * simulated latency. `error` fails every read (500); `not-ready` fails every call with the
 * gateway's 503 PLUGIN_NOT_READY. A command with a fixture is answered by it (resolvers can
 * keep state, so paging/search/sort and writes behave like the server); others are acknowledged.
 */
export class FixtureDataPort implements WidgetDataPortLike {
  readonly calls: CallRecord[] = [];
  private seq = 0;

  constructor(
    private readonly fixtures: FixtureSet,
    private scenario: FixtureScenario = 'default',
    private readonly onCall?: Recorder,
    private readonly latencyMs = 0,
    private readonly permissions: readonly string[] = [],
  ) {}

  private record(r: CallRecord) {
    const rec = { ...r, seq: ++this.seq };
    this.calls.push(rec);
    this.onCall?.(rec);
  }

  setScenario(s: FixtureScenario) {
    this.scenario = s;
  }

  private wait(): Promise<void> {
    return this.latencyMs > 0 ? new Promise((r) => setTimeout(r, this.latencyMs)) : Promise.resolve();
  }

  private answer(name: string, kind: 'source' | 'command', params: Record<string, string>, body: unknown): unknown {
    if (this.scenario === 'not-ready') {
      throw new ApiError({
        status: 503, code: 'PLUGIN_NOT_READY', title: 'Plugin not ready',
        detail: 'This plugin is not enabled for the organization.',
      });
    }
    const fx = this.fixtures[name];
    if (!fx) {
      if (kind === 'command') return { ok: true };
      throw new ApiError({ status: 0, code: 'NO_FIXTURE', title: 'No fixture', detail: `missing fixture for ${name}` });
    }
    if (this.scenario === 'error' && kind === 'source') {
      throw new ApiError({ status: 500, code: 'FIXTURE_ERROR', title: 'Server error', detail: `fixture error scenario for ${name}` });
    }
    const a = this.scenario in fx ? fx[this.scenario] : fx.default;
    return typeof a === 'function'
      ? (a as (req: FixtureRequest) => unknown)({ params, body, permissions: this.permissions, scenario: this.scenario })
      : a ?? null;
  }

  async request<T>(source: string, params?: Record<string, string>): Promise<T> {
    return this.run<T>(source, 'source', params, undefined);
  }

  async command<T>(command: string, body?: unknown, params?: Record<string, string>): Promise<T> {
    return this.run<T>(command, 'command', params, body);
  }

  private async run<T>(name: string, kind: 'source' | 'command', params: Record<string, string> | undefined, body: unknown): Promise<T> {
    const t0 = performance.now();
    await this.wait();
    try {
      const data = this.answer(name, kind, params ?? {}, body) as T;
      this.record({
        name, kind, resolved: 'fixture', params, ms: Math.round(performance.now() - t0),
        response: kind === 'command' ? { request: body ?? null, answer: data } : data,
        status: kind === 'source' && isEmpty(data) ? 'empty' : 'ok',
      });
      return data;
    } catch (e) {
      this.record({
        name, kind, resolved: 'fixture', params, ms: Math.round(performance.now() - t0), status: statusOf(e),
        detail: e instanceof Error ? e.message : String(e),
      });
      throw e;
    }
  }
}
