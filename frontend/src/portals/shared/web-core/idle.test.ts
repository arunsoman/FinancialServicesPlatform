import { describe, expect, it } from 'vitest';
import { idleVerdict, WARN_BEFORE_SECONDS } from './idle';

const T = Date.parse('2026-11-24T09:00:00Z');
const s = (n: number) => n * 1000;

describe('idleVerdict — sign out after real inactivity, warn a minute before', () => {
  it('an active person stays signed in', () => {
    expect(idleVerdict(T + s(60), T, 1800, null)).toEqual({ kind: 'active' });
  });
  it('warns in the last minute of the idle limit, counting down', () => {
    expect(idleVerdict(T + s(1800 - WARN_BEFORE_SECONDS), T, 1800, null)).toEqual({ kind: 'warn', secondsLeft: 60, why: 'idle' });
    expect(idleVerdict(T + s(1795), T, 1800, null)).toEqual({ kind: 'warn', secondsLeft: 5, why: 'idle' });
  });
  it('signs out at the idle limit', () => {
    expect(idleVerdict(T + s(1800), T, 1800, null)).toEqual({ kind: 'sign-out', why: 'idle' });
  });
  it('the absolute end wins when it comes first, whatever the activity', () => {
    const endsAt = T + s(30);
    expect(idleVerdict(T + s(10), T + s(10), 1800, endsAt)).toEqual({ kind: 'warn', secondsLeft: 20, why: 'expired' });
    expect(idleVerdict(T + s(31), T + s(31), 1800, endsAt)).toEqual({ kind: 'sign-out', why: 'expired' });
  });
});
