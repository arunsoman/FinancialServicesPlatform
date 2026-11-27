/**
 * F-12 client push + unread plumbing (non-component: react-refresh-safe).
 *
 * Slice: in-app unread badge (poll every 30s via the cheap unread-count endpoint) + Web Push
 * subscription (service worker `fsp-push-sw.js`, VAPID key from the server config endpoint).
 * The server payload is data-minimized (AGENTS §2c) and money mutations are never driven
 * from a notification — the link only routes; core re-checks every permission.
 *
 * APNs/FCM devices follow the SAME contract (POST /api/notifications/push/subscriptions with
 * platform APNS|FCM) — native apps subscribe with their own device tokens; no client change.
 */
import { apiGet, apiPost, apiDelete, ApiError } from './api';

const UNREAD_POLL_MS = 30_000;

export interface PushConfig {
  webpushEnabled: boolean;
  vapidPublicKey?: string;
}

export type UnreadListener = (unread: number | null) => void;

const listeners = new Set<UnreadListener>();
let pollTimer: ReturnType<typeof setInterval> | null = null;
let lastUnread: number | null = null;

export function onUnread(fn: UnreadListener): () => void {
  listeners.add(fn);
  fn(lastUnread);
  return () => listeners.delete(fn);
}

function emit(unread: number | null) {
  lastUnread = unread;
  for (const l of listeners) l(unread);
  // system badge where supported (mobile web / installed PWA); never throws
  try {
    if (unread === null) navigator.clearAppBadge?.();
    else navigator.setAppBadge?.(unread);
  } catch {
    /* unsupported: badge is cosmetic */
  }
}

/** One poll: unread count for the signed-in person (fail-soft — badge is cosmetic). */
export async function pollUnread(): Promise<number | null> {
  try {
    // `pending` = not acted upon and not snoozed (the bell); older cores only send `unread`
    const r = await apiGet<{ unread: number; pending?: number }>('/api/notifications/unread-count');
    const n = r.pending ?? r.unread;
    emit(n);
    return n;
  } catch {
    // 403 (no notification.view) or session dropped — badge just stays unset
    emit(null);
    return null;
  }
}

export function startUnreadPolling() {
  if (pollTimer) return;
  void pollUnread();
  pollTimer = setInterval(() => void pollUnread(), UNREAD_POLL_MS);
}

export function stopUnreadPolling() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
  emit(null);
}

// ---------------- Web Push subscription ----------------

function urlB64ToUint8Array(b64: string): Uint8Array {
  const pad = '='.repeat((4 - (b64.length % 4)) % 4);
  const raw = atob((b64 + pad).replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from([...raw].map((c) => c.charCodeAt(0)));
}

/**
 * Enable push for this browser/device: registers the SW, asks for the Notification
 * permission, subscribes with the server's VAPID key and registers the subscription.
 * Best-effort: returns false when unsupported/denied/insecure-context (badge keeps working).
 */
export async function enableWebPush(): Promise<boolean> {
  if (!('serviceWorker' in navigator) || !('PushManager' in window) || typeof Notification === 'undefined') {
    return false;
  }
  try {
    const cfg = await apiGet<PushConfig>('/api/notifications/push/config');
    if (!cfg.webpushEnabled || !cfg.vapidPublicKey) return false;

    if (Notification.permission === 'denied') return false;
    const reg = await navigator.serviceWorker.register('/fsp-push-sw.js');
    await navigator.serviceWorker.ready;
    if (Notification.permission !== 'granted') {
      const result = await Notification.requestPermission();
      if (result !== 'granted') return false;
    }
    const existing = await reg.pushManager.getSubscription();
    const sub =
      existing ??
      (await reg.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlB64ToUint8Array(cfg.vapidPublicKey) as BufferSource,
      }));
    const json = sub.toJSON();
    const keys = (json.keys ?? {}) as { p256dh?: string; auth?: string };
    await apiPost('/api/notifications/push/subscriptions', {
      platform: 'WEBPUSH',
      endpoint: sub.endpoint,
      p256dh: keys.p256dh ?? null,
      auth: keys.auth ?? null,
      userAgent: navigator.userAgent,
    });
    return true;
  } catch (e) {
    if (e instanceof ApiError && (e.status === 401 || e.status === 403)) return false; // not signed in / no perm
    return false;
  }
}

/** Browser-side unsubscribe: drop the push subscription and retire the server row. */
export async function disableWebPush(): Promise<void> {
  try {
    const reg = await navigator.serviceWorker.getRegistration('/fsp-push-sw.js');
    const sub = await reg?.pushManager.getSubscription();
    if (sub) {
      await apiDelete(
        '/api/notifications/push/subscriptions/' + encodeURIComponent(sub.endpoint),
      );
      await sub.unsubscribe();
    }
  } catch {
    /* best-effort */
  }
}

/**
 * Sign-in flow: start polling + enable push once per session. Push enabling is deferred one
 * tick so sign-in navigation isn't blocked by the permission prompt.
 */
export function onSessionAuthed() {
  startUnreadPolling();
  setTimeout(() => void enableWebPush(), 1500);
}
