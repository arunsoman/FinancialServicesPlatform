/**
 * Double-submit guard — no button, form or mutation can fire twice in a burst.
 *
 * Three layers, all installed once per page from `portals/boot.ts`:
 *  1. **Cooldown** — a capture-phase `click`/`submit` listener on `window` drops a repeat
 *     activation of the same button/form inside {@link COOLDOWN_MS} (double-click, key repeat,
 *     Enter + click). It runs before React's root listener, so the handler never sees it.
 *  2. **In-flight hold** — a mutation started while handling a click/submit ({@link holdWhile},
 *     called by `api()` for every non-GET) keeps that button/form locked until the request
 *     settles, so a slow POST cannot be resubmitted while it is still on the wire.
 *  3. **Request dedupe** — `api()` joins an identical in-flight mutation (see api.ts), the
 *     backstop for programmatic double calls that never pass through a click.
 *
 * Opt out for controls that are meant to be pressed rapidly (steppers, carousel arrows) with
 * `data-repeatable` on the element or an ancestor.
 */
export const COOLDOWN_MS = 700;

/** Anything a user "presses". Links are excluded: a repeated SPA navigation is harmless. */
export const ACTIONABLE =
  'button, input[type="submit"], input[type="button"], input[type="image"], [role="button"]';

const BUSY_ATTR = 'data-fsp-busy';

interface GuardTarget {
  setAttribute(name: string, value: string): void;
  removeAttribute(name: string): void;
}

/** Pure lock bookkeeping — DOM-agnostic so it is unit-testable without a browser. */
export class SubmitLocks<T extends GuardTarget = GuardTarget> {
  private readonly until = new WeakMap<T, number>();
  private readonly inflight = new WeakMap<T, number>();
  /** Targets activated in the current task; mutations started now are attributed to them. */
  private readonly armed = new Set<T>();

  constructor(private readonly cooldownMs = COOLDOWN_MS) {}

  isLocked(t: T, now: number): boolean {
    return (this.inflight.get(t) ?? 0) > 0 || now < (this.until.get(t) ?? 0);
  }

  /**
   * Records an activation. Returns false when it must be dropped (a repeat inside the
   * cooldown, or the target is still holding an in-flight request).
   */
  activate(t: T, now: number): boolean {
    if (this.isLocked(t, now)) return false;
    this.until.set(t, now + this.cooldownMs);
    this.armed.add(t);
    return true;
  }

  /** Ends the attribution window for the activations of the current task. */
  disarm(t: T): void {
    this.armed.delete(t);
  }

  /** Keeps every currently-armed target locked until `p` settles. Returns `p` unchanged. */
  hold<R>(p: Promise<R>): Promise<R> {
    if (this.armed.size === 0) return p;
    const owners = [...this.armed];
    for (const t of owners) {
      const n = (this.inflight.get(t) ?? 0) + 1;
      this.inflight.set(t, n);
      t.setAttribute(BUSY_ATTR, '');
      t.setAttribute('aria-busy', 'true');
    }
    const release = () => {
      for (const t of owners) {
        const n = (this.inflight.get(t) ?? 1) - 1;
        if (n > 0) {
          this.inflight.set(t, n);
          continue;
        }
        this.inflight.delete(t);
        t.removeAttribute(BUSY_ATTR);
        t.removeAttribute('aria-busy');
      }
    };
    p.then(release, release);
    return p;
  }
}

const locks = new SubmitLocks<Element>();

/** Keep the control that triggered the current action locked until `p` settles. */
export function holdWhile<R>(p: Promise<R>): Promise<R> {
  return locks.hold(p);
}

function drop(ev: Event): void {
  ev.preventDefault();
  ev.stopImmediatePropagation();
}

function guard(target: Element | null, ev: Event): void {
  if (!target || target.closest('[data-repeatable]')) return;
  if (!locks.activate(target, performance.now())) {
    drop(ev);
    return;
  }
  // React runs the handler synchronously and api() registers its hold within the same task
  // (microtasks included); the next macrotask closes the attribution window.
  setTimeout(() => locks.disarm(target), 0);
}

let installed = false;

/** Idempotent; called from `portals/boot.ts` so every portal + the workbench get it. */
export function installSubmitGuard(): void {
  if (installed || typeof window === 'undefined') return;
  installed = true;
  window.addEventListener(
    'click',
    (ev) => {
      const el = ev.target instanceof Element ? ev.target.closest(ACTIONABLE) : null;
      // a disabled control never dispatches click, but aria-disabled ones do
      if (el?.getAttribute('aria-disabled') === 'true') return;
      guard(el, ev);
    },
    true,
  );
  window.addEventListener(
    'submit',
    (ev) => guard(ev.target instanceof HTMLFormElement ? ev.target : null, ev),
    true,
  );
}
