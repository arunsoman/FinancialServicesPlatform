/**
 * Pure idle-timer rules (tested in idle.test.ts): what the shell should show for a given moment.
 * "Activity" is a person using the page — pointer, keys, scroll, touch — never background polling.
 */
export const WARN_BEFORE_SECONDS = 60;

export type IdleVerdict =
  | { kind: 'active' }
  | { kind: 'warn'; secondsLeft: number; why: 'idle' | 'expired' }
  | { kind: 'sign-out'; why: 'idle' | 'expired' };

/**
 * @param now            epoch ms
 * @param lastActivity   epoch ms of the last real user activity (any tab of this portal)
 * @param idleSeconds    the BFF's idle limit
 * @param endsAt         epoch ms of the session's absolute end, or null
 */
export function idleVerdict(now: number, lastActivity: number, idleSeconds: number, endsAt: number | null): IdleVerdict {
  const idleLeft = idleSeconds - Math.floor((now - lastActivity) / 1000);
  const endLeft = endsAt === null ? Number.POSITIVE_INFINITY : Math.floor((endsAt - now) / 1000);
  const why: 'idle' | 'expired' = endLeft < idleLeft ? 'expired' : 'idle';
  const left = Math.min(idleLeft, endLeft);
  if (left <= 0) return { kind: 'sign-out', why };
  if (left <= WARN_BEFORE_SECONDS) return { kind: 'warn', secondsLeft: left, why };
  return { kind: 'active' };
}

/** localStorage key holding the last activity of a portal, shared by its tabs. */
export const activityKey = (storageKey: string) => `${storageKey}.lastActivity`;
