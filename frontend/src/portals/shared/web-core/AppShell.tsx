/**
 * AppShell — the portal chrome (SME nav-shell + admin/org shell port):
 * topbar (brand w/ --portal-accent, user, sign out) + sidebar drawn from the portal's
 * PUBLISHED, signature-verified layout filtered by held permission keys, + main scroll
 * region. Sidebar collapses to a top-nav drawer on small screens.
 */
import { NavLink, Outlet, Link } from 'react-router-dom';
import type { ReactNode } from 'react';
import { doLogout, useSession } from './session-core';
import { IdleSignOut } from './IdleSignOut';
import type { PortalConfig } from '../../portal-config';
import { layoutNavEntries, useMenuAccess, useSignedLayout } from '../../signed/signed-layout';
import { getWidget } from '../widgets/registry';
import { useState, useEffect } from 'react';
import { onUnread } from './push-core';
import { NotificationBell } from './notifications/NotificationBell';

export function AppShell({
  portal,
  showNav = true,
  children,
}: {
  portal: PortalConfig;
  /** false for single-screen portals (e.g. a one-screen tool): no menu, full-width main. */
  showNav?: boolean;
  /** ignored — the router renders the matched child via <Outlet/> */
  children?: ReactNode;
}) {
  void children;
  const { user, permissions, actAs, exitActAs } = useSession();
  const [exiting, setExiting] = useState(false);
  // F-12 unread badge: pushed from push-core's poll (30s) + Web Push-driven refreshes
  const [unread, setUnread] = useState<number | null>(null);
  useEffect(() => onUnread(setUnread), []);

  const layout = useSignedLayout();
  const access = useMenuAccess();
  // the sidebar is ONLY the verified, published layout (SignedMenuGate renders "No menu
  // configured" before this shell when a portal has none — there is no coded fallback menu)
  const nav = layout ? layoutNavEntries(layout, permissions, getWidget, access) : [];

  const navClass = (isActive: boolean) =>
    'block rounded-lg px-3 py-1.5 text-[13px] transition-colors ' +
    (isActive
      ? 'font-extrabold text-invert'
      : 'text-dim hover:bg-raised hover:text-text hover:bg-raised');

  return (
    <div
      className="grid h-[100dvh] max-h-[100dvh] grid-rows-[auto_1fr] overflow-hidden"
      style={{ ['--portal-accent' as string]: portal.accent }}
    >
      <header className="border-b border-line bg-panel">
        <div className="flex items-center justify-between gap-4 px-4 py-2.5">
          <Link to="/" className="flex items-baseline gap-1.5">
            <span className="text-sm font-bold tracking-[.12em] text-invert">FSP</span>
            <span className="text-xs font-bold uppercase tracking-[.12em]" style={{ color: 'var(--portal-accent)' }}>
              {portal.label}
            </span>
          </Link>
          <div className="flex items-center gap-3">
            <NotificationBell pending={unread} userId={user?.userId} orgId={actAs?.targetTenantId ?? user?.tenantId ?? ''} />
            {user && (
              <div className="text-right leading-tight">
                <div className="text-xs font-semibold">{user.fullName}</div>
                <div className="text-dim text-[10px] uppercase tracking-wide">
                  {user.tenantName ?? user.username} · {user.roles?.join(', ') ?? ''}
                </div>
              </div>
            )}
            <button className="fsp-btn fsp-btn-ghost px-3 py-1 text-xs" onClick={() => doLogout({ basePath: portal.basePath, storageKey: portal.storageKey })}>
              Sign out
            </button>
          </div>
        </div>
        {actAs && (
          <div
            data-testid="act-as-banner"
            role="status"
            className="flex items-center justify-between gap-3 border-t border-line bg-raised px-4 py-2"
          >
            {/* ui-std §5.2: an acting principal is always NAMEABLE — person on record, target spelled out. */}
            <div className="truncate text-xs">
              {actAs.personName ?? user?.username} acting as{' '}
              <strong>{actAs.targetTenantName ?? actAs.targetTenantId}</strong>
              {actAs.path.length > 0 && (
                <span className="text-dim">
                  {' '}({actAs.path.map((p) => p.name).filter(Boolean).join(' › ')})
                </span>
              )}
              {' '}— every action is audited as you
            </div>
            <button
              data-testid="act-as-exit"
              className="fsp-btn fsp-btn-ghost shrink-0 px-3 py-1 text-xs"
              disabled={exiting}
              onClick={async () => {
                setExiting(true);
                try {
                  await exitActAs();
                } finally {
                  setExiting(false);
                }
              }}
            >
              Exit
            </button>
          </div>
        )}
      </header>

      <div
        className={
          showNav ? 'grid min-h-0 grid-rows-[auto_1fr] md:grid-cols-[210px_1fr] md:grid-rows-1' : 'grid min-h-0'
        }
      >
        {showNav && (
        <nav aria-label="Portal navigation" className="scrollbar-none overflow-x-auto border-b border-line bg-panel px-2 py-2 md:overflow-y-auto md:border-b-0 md:border-r">
          {nav.map((n) =>
            n.kind === 'group' ? (
              <div key={`g-${n.label}`} className="fsp-eyebrow mt-2 hidden px-3 pb-1 md:block first:mt-0">
                {n.label}
              </div>
            ) : (
              <NavLink
                key={n.link}
                to={n.link}
                className={({ isActive }) => navClass(isActive)}
                style={({ isActive }) =>
                  isActive
                    ? { backgroundColor: 'var(--portal-accent)', color: 'var(--color-text-invert)' }
                    : undefined
                }
              >
                <span className="mr-2" aria-hidden>{n.icon}</span>
                {n.label}
                {n.link === '/approvals' && unread !== null && unread > 0 && (
                  <span
                    data-testid="approvals-badge"
                    aria-label={unread + ' pending approvals'}
                    className="ml-2 inline-flex min-w-[1.25rem] items-center justify-center rounded-full bg-accent px-1.5 text-[10px] font-bold text-invert"
                  >
                    {unread > 99 ? '99+' : unread}
                  </span>
                )}
              </NavLink>
            ),
          )}
        </nav>
        )}

        <main className="min-h-0 overflow-y-auto px-4 py-5 md:px-6" tabIndex={0} aria-label="Main content">
          <Outlet />
          <IdleSignOut portal={portal} />
        </main>
      </div>
    </div>
  );
}

export type ShellSlot = ReactNode;
