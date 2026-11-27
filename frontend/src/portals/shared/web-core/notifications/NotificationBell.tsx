import { useEffect, useState } from 'react';
import { NotificationCenter } from './NotificationCenter';
import { sessionStore, shouldAutoOpen } from './model';

export interface NotificationBellProps {
  /** pending count from push-core's poll; null = no notification.view / signed out → no bell */
  pending: number | null;
  userId: string | undefined;
  orgId: string;
}

/**
 * Topbar bell: badge = notifications not acted upon and not snoozed. On sign-in, if anything
 * is waiting, the centre opens by itself once per browser session.
 */
export function NotificationBell({ pending, userId, orgId }: NotificationBellProps) {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (shouldAutoOpen(userId, pending, sessionStore())) setOpen(true);
  }, [userId, pending]);

  if (pending === null) return null;
  const label = pending > 0 ? `Notifications: ${pending} need your action` : 'Notifications';
  return (
    <>
      <button
        type="button"
        data-testid="notification-bell"
        aria-label={label}
        title={label}
        className="relative grid h-8 w-8 place-items-center rounded-full border border-line text-dim hover:border-line-strong hover:text-[var(--color-text)]"
        onClick={() => setOpen(true)}
      >
        <svg aria-hidden viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
          <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
        </svg>
        {pending > 0 && (
          <span
            data-testid="notification-badge"
            aria-hidden
            className="absolute -right-1 -top-1 inline-flex min-w-[1.1rem] items-center justify-center rounded-full bg-accent px-1 text-[10px] font-bold text-invert"
          >
            {pending > 99 ? '99+' : pending}
          </span>
        )}
      </button>
      {open && <NotificationCenter orgId={orgId} onClose={() => setOpen(false)} />}
    </>
  );
}
