/**
 * Double-submit guard: a burst of presses / identical mutations reaches the server ONCE.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SubmitLocks } from './submit-guard';
import { api, apiPost } from './api';

class FakeEl {
  readonly attrs = new Map<string, string>();
  setAttribute(n: string, v: string) { this.attrs.set(n, v); }
  removeAttribute(n: string) { this.attrs.delete(n); }
}

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

describe('SubmitLocks · one press = one action', () => {
  it('drops a repeat activation inside the cooldown and allows it after', () => {
    const l = new SubmitLocks<FakeEl>(700);
    const b = new FakeEl();
    expect(l.activate(b, 1000)).toBe(true);
    expect(l.activate(b, 1001)).toBe(false);
    expect(l.activate(b, 1699)).toBe(false);
    expect(l.activate(b, 1700)).toBe(true);
  });

  it('locks only the pressed control', () => {
    const l = new SubmitLocks<FakeEl>(700);
    const a = new FakeEl();
    const b = new FakeEl();
    expect(l.activate(a, 0)).toBe(true);
    expect(l.activate(b, 1)).toBe(true);
  });

  it('holds the control past the cooldown while its request is in flight, then releases', async () => {
    const l = new SubmitLocks<FakeEl>(700);
    const b = new FakeEl();
    const d = deferred<void>();
    l.activate(b, 0);
    void l.hold(d.promise);
    l.disarm(b);
    expect(b.attrs.has('data-fsp-busy')).toBe(true);
    expect(l.activate(b, 60_000)).toBe(false); // still on the wire a minute later
    d.reject(new Error('boom'));
    await d.promise.catch(() => undefined);
    await Promise.resolve();
    expect(b.attrs.has('data-fsp-busy')).toBe(false);
    expect(l.activate(b, 60_001)).toBe(true);
  });

  it('does not attribute requests started after the activation task ended', () => {
    const l = new SubmitLocks<FakeEl>(700);
    const b = new FakeEl();
    l.activate(b, 0);
    l.disarm(b);
    void l.hold(new Promise<void>(() => undefined));
    expect(b.attrs.has('data-fsp-busy')).toBe(false);
  });
});

describe('api() · identical in-flight mutations are sent once', () => {
  afterEach(() => vi.unstubAllGlobals());

  function stubFetch() {
    const gate = deferred<void>();
    const calls: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/bff/csrf') return new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 't' }));
      calls.push(`${init?.method} ${url} ${String(init?.body ?? '')}`);
      await gate.promise;
      return new Response(JSON.stringify({ ok: true }), { status: 200 });
    }));
    return { gate, calls };
  }

  it('a double-fired POST hits the server once and both callers get the result', async () => {
    const { gate, calls } = stubFetch();
    const p1 = apiPost<{ ok: boolean }>('/api/v1/things', { a: 1 });
    const p2 = apiPost<{ ok: boolean }>('/api/v1/things', { a: 1 });
    expect(p2).toBe(p1);
    gate.resolve();
    expect(await p1).toEqual({ ok: true });
    expect(calls).toHaveLength(1);
  });

  it('different bodies are distinct actions', async () => {
    const { gate, calls } = stubFetch();
    const p1 = apiPost('/api/v1/things', { a: 1 });
    const p2 = apiPost('/api/v1/things', { a: 2 });
    gate.resolve();
    await Promise.all([p1, p2]);
    expect(calls).toHaveLength(2);
  });

  it('after the first settles, a deliberate resubmit is sent again', async () => {
    const { gate, calls } = stubFetch();
    gate.resolve();
    await apiPost('/api/v1/things', { a: 1 });
    await apiPost('/api/v1/things', { a: 1 });
    expect(calls).toHaveLength(2);
  });

  it('dedupe:false opts out', async () => {
    const { gate, calls } = stubFetch();
    const p1 = api('/api/v1/things', { method: 'POST', body: { a: 1 }, dedupe: false });
    const p2 = api('/api/v1/things', { method: 'POST', body: { a: 1 }, dedupe: false });
    gate.resolve();
    await Promise.all([p1, p2]);
    expect(calls).toHaveLength(2);
  });
});
