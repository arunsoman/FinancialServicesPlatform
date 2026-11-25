/**
 * Portal app factory — builds the router for one portal from THE menu
 * registry (routes and guards read the same truth; fail-closed). Admin and
 * org portals are this one app differing only by identity + PortalId, exactly
 * like the SME org-portal/portal.config pattern.
 *
 * Route guards: session → eligibility → registry permission set, in order.
 * Absolute navigate targets are basename-aware (each portal boots with its
 * own basename), so '/login' and '/no-access' stay inside the portal.
 */
import { Link, Navigate, Outlet, RouterProvider, createBrowserRouter } from 'react-router-dom';
import { AppShell } from './shared/web-core/AppShell';
import { FullscreenSpinner } from './shared/web-core/guards';
import { LoginForm } from './shared/web-core/login-form';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SessionProvider } from './shared/web-core/session';
import { can, useSession } from './shared/web-core/session-core';
import { menuFor, permsForRoute } from './shared/web-core/menu';
import type { MenuItem, PortalId } from './shared/web-core/menu';

import type { PortalConfig } from './portal-config';
import { SignedMenuGate } from './signed/SignedMenuGate';
import { HomeRedirect } from './HomeRedirect';
import { LayoutScreen } from './LayoutScreen';
import { SCREENS } from './screens-map';
import type { ScreenProps } from './screens';

/* ------------------------------------------------------------ guarded screen */
function GuardedScreen({ portalId, item }: { portalId: PortalId; item: MenuItem }) {
  const { status, permissions, eligible } = useSession();
  if (status === 'loading') return <FullscreenSpinner label="Restoring session" />;
  if (status === 'anon') return <Navigate to="/login" replace />;
  if (eligible === undefined) return <FullscreenSpinner label="Checking portal access" />;
  if (!eligible) return <Navigate to="/no-access" replace />;
  if (!can(permissions, permsForRoute(portalId, item.link))) return <Navigate to="/no-access" replace />;
  const Screen = SCREENS[item.link] ?? SCREENS['*'];
  return <Screen portalId={portalId} link={item.link} widgets={item.widgets} label={item.label} config={item.config} span={item.span} />;
}

/* --------------------------------------------------------------- login route */
function LoginRoute({ portal }: { portal: PortalConfig }) {
  const { status, eligible } = useSession();
  if (status === 'loading') return <FullscreenSpinner label="Checking session" />;
  if (status === 'authed' && eligible) return <Navigate to="/" replace />;
  if (status === 'authed' && eligible === false) return <Navigate to="/no-access" replace />;
  return <LoginForm portalLabel={portal.label} />;
}

/* ------------------------------------------------------------------ no access */
function NoAccess({ portal }: { portal: PortalConfig }) {
  const { user, eligible } = useSession();
  // Signed in AND allowed in this portal: only the page that sent you here is out of reach (a
  // screen you lack rights for, or one whose plugin is not running). The URL /no-access survives
  // a reload, so always offer the way back instead of a dead end that blames your permissions.
  if (user && eligible) {
    return (
      <div className="grid h-[100dvh] place-items-center">
        <div className="fsp-panel max-w-md p-8 text-center">
          <div className="fsp-eyebrow mb-2">{portal.label}</div>
          <h1 className="mb-3 text-xl">That page is not available</h1>
          <p className="text-dim mb-5 text-sm leading-relaxed">
            {`You are signed in (${user.username}) and can use this portal, but the page you opened needs a right you do not hold, or is not available right now.`}
          </p>
          <Link className="fsp-btn" to="/">Go to the portal home</Link>
        </div>
      </div>
    );
  }
  return (
    <div className="grid h-[100dvh] place-items-center">
      <div className="fsp-panel max-w-md p-8 text-center">
        <div className="fsp-eyebrow mb-2">{portal.label}</div>
        <h1 className="mb-3 text-xl">No access to this portal</h1>
        <p className="text-dim text-sm leading-relaxed">
          {user
            ? `You are signed in (${user.username}), but hold no permission this portal shows. Use the portal for your organization type, or ask an administrator for rights.`
            : 'This portal is for a different part of the platform.'}
        </p>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------- factory */
function Router({ portal, portalId }: { portal: PortalConfig; portalId: PortalId }) {
  const menu = menuFor(portalId);
  const router = createBrowserRouter(
    [
      { path: 'login', element: <LoginRoute portal={portal} /> },
      {
        path: '',
        element: (
          <SignedMenuGate portal={portal.deliveryKey} label={portal.label}>
            <AppShell portal={portal}>
              <Outlet />
            </AppShell>
          </SignedMenuGate>
        ),
        children: [
          // home: first published screen the user can see
          { index: true, element: <HomeRedirect /> },
          // screens of the published, signature-verified layout (SME p/:menu/:screen)
          { path: 'p/:menuId/:screenId', element: <LayoutScreen /> },
          ...menu.map((item) => ({
            path: item.link.replace(/^\//, ''),
            element: <GuardedScreen portalId={portalId} item={item} />,
          })),
          { path: 'no-access', element: <NoAccess portal={portal} /> },
        ],
      },
      { path: '*', element: <Navigate to="/" replace /> },
    ],
    { basename: portal.basePath || '/' },
  );
  return <RouterProvider router={router} />;
}

/**
 * One query client per portal page. ui-std §7: 1 retry for idempotent GETs (never on 401/403/404),
 * ZERO automatic retries for mutations — outcomes are surfaced, never silently re-sent.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: false,
      retry: (failureCount, error) => {
        const status = (error as { status?: number }).status ?? 0;
        if (status === 401 || status === 403 || status === 404) return false;
        return failureCount < 1;
      },
    },
    mutations: { retry: 0 },
  },
});

/** Boot one portal: query client + session context + router. */
export function PortalRoot({ portal, portalId }: { portal: PortalConfig; portalId: PortalId }) {
  return (
    <QueryClientProvider client={queryClient}>
      <SessionProvider portal={portal}>
        <Router portal={portal} portalId={portalId} />
      </SessionProvider>
    </QueryClientProvider>
  );
}

export type { ScreenProps };