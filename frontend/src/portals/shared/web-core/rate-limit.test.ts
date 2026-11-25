/**
 * Staging, 2026-11-25: the gateway answered part of a page load with 429 RATE_LIMITED and the
 * portal showed a signed-in platform admin "No access" with only permission-free menu items.
 * A 429 means "not processed, try again" — never "you have no rights".
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiGet, apiPost } from './api';
import { loadPermissions } from './session-core';

const rateLimited = () => new Response(JSON.stringify({ code: 'RATE_LIMITED', detail: 'slow down', status: 429 }),
  { status: 429, headers: { 'Retry-After': '1' } });

function stub(responses: Array<() => Response>) {
  const calls: Array<{ url: string; key: string | null }> = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/bff/csrf') return new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 't' }));
    const h = (init?.headers ?? {}) as Record<string, string>;
    calls.push({ url, key: h['Idempotency-Key'] ?? null });
    const next = responses.shift();
    return next ? next() : new Response('{}', { status: 500 });
  }));
  return calls;
}

describe('a 429 from the gateway is retried, not shown as a denial', () => {
  afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

  it('a GET that is rate limited once succeeds on the retry', async () => {
    vi.useFakeTimers();
    const calls = stub([rateLimited, () => new Response(JSON.stringify({ portal: 'admin' }))]);
    const p = apiGet<{ portal: string }>('/api/ui/menus/admin');
    await vi.runAllTimersAsync();
    expect(await p).toEqual({ portal: 'admin' });
    expect(calls).toHaveLength(2);
  });

  it('a retried mutation keeps its Idempotency-Key (one logical action)', async () => {
    vi.useFakeTimers();
    const calls = stub([rateLimited, () => new Response('{}')]);
    const p = apiPost('/api/v1/things', { a: 1 });
    await vi.runAllTimersAsync();
    await p;
    expect(calls).toHaveLength(2);
    expect(calls[1].key).toBe(calls[0].key);
  });

  it('permissions that keep failing are an error, never an empty permission list', async () => {
    vi.useFakeTimers();
    stub([rateLimited, rateLimited, rateLimited, rateLimited]);
    const p = loadPermissions();
    const settled = p.then(() => 'resolved', () => 'rejected');
    await vi.runAllTimersAsync();
    expect(await settled).toBe('rejected');
  });

  it('a real 403 on permissions still means no permissions', async () => {
    stub([() => new Response(JSON.stringify({ code: 'FORBIDDEN' }), { status: 403 })]);
    expect(await loadPermissions()).toEqual([]);
  });
});
