import { describe, expect, it } from 'vitest';
import { ago, shouldAutoOpen, snoozeOptions } from './model';

function memoryStore() {
  const m = new Map<string, string>();
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v) };
}

describe('snoozeOptions', () => {
  it('offers later today only while 17:00 is more than an hour away', () => {
    const morning = new Date(2026, 10, 23, 10, 0); // Mon 10:00
    expect(snoozeOptions(morning).map((o) => o.id)).toEqual(['hour', 'afternoon', 'tomorrow', 'nextWeek']);
    const late = new Date(2026, 10, 23, 16, 30);
    expect(snoozeOptions(late).map((o) => o.id)).toEqual(['hour', 'tomorrow', 'nextWeek']);
  });

  it('computes concrete instants: +1h, today 17:00, tomorrow 09:00, the NEXT Monday 09:00', () => {
    const mon = new Date(2026, 10, 23, 10, 0); // a Monday
    const [hour, afternoon, tomorrow, nextWeek] = snoozeOptions(mon);
    expect(hour.until.getTime() - mon.getTime()).toBe(3_600_000);
    expect([afternoon.until.getDate(), afternoon.until.getHours()]).toEqual([23, 17]);
    expect([tomorrow.until.getDate(), tomorrow.until.getHours()]).toEqual([24, 9]);
    // on a Monday, "next week" is 7 days out — never today
    expect([nextWeek.until.getDay(), nextWeek.until.getDate(), nextWeek.until.getHours()]).toEqual([1, 30, 9]);
    const sat = new Date(2026, 10, 28, 10, 0);
    expect(snoozeOptions(sat).at(-1)!.until.getDate()).toBe(30);
  });

  it('every option is within the server window (1 min – 30 days)', () => {
    const now = new Date(2026, 10, 23, 23, 59);
    for (const o of snoozeOptions(now)) {
      const d = o.until.getTime() - now.getTime();
      expect(d).toBeGreaterThan(60_000);
      expect(d).toBeLessThan(30 * 86_400_000);
    }
  });
});

describe('shouldAutoOpen', () => {
  it('opens once per person per session, only when something is pending', () => {
    const store = memoryStore();
    expect(shouldAutoOpen('u1', 0, store)).toBe(false);
    expect(shouldAutoOpen('u1', null, store)).toBe(false);
    expect(shouldAutoOpen(undefined, 3, store)).toBe(false);
    expect(shouldAutoOpen('u1', 2, store)).toBe(true);
    expect(shouldAutoOpen('u1', 5, store)).toBe(false); // already shown this session
    expect(shouldAutoOpen('u2', 1, store)).toBe(true); // a different person signed in
  });

  it('still opens when storage is blocked', () => {
    const blocked = { getItem: () => { throw new Error('denied'); }, setItem: () => { throw new Error('denied'); } };
    expect(shouldAutoOpen('u1', 1, blocked)).toBe(true);
    expect(shouldAutoOpen('u1', 1, null)).toBe(true);
  });
});

describe('ago', () => {
  it('is compact', () => {
    const now = new Date('2026-11-23T12:00:00Z');
    expect(ago('2026-11-23T11:59:40Z', now)).toBe('just now');
    expect(ago('2026-11-23T11:55:00Z', now)).toBe('5 min ago');
    expect(ago('2026-11-23T09:00:00Z', now)).toBe('3 h ago');
    expect(ago('2026-11-21T12:00:00Z', now)).toBe('2 d ago');
    expect(ago('nope', now)).toBe('');
  });
});
