/**
 * Load-then-render boot gate. Fetches the portal's published menus from core, checks their shape,
 * and only then renders children — with the layout on SignedLayoutCtx for the shell + layout
 * screens. A load error shows "Couldn't load the menus" with a retry.
 *
 * Policy: a portal's menu comes ONLY from its published layout. Nothing published renders
 * "No menu configured" for every portal — never the build-time menu registry.
 *
 * Authentication comes first: the menus are an authenticated read, so the gate only loads once a
 * session exists. No session, or a 401 (token expired/revoked), forces re-authentication through
 * the hosted IdP sign-in (FAPI2 error-UX map: "invalid token → force re-authentication").
 *
 * Then the person's menu access (the Permissions Matrix cells their roles hold on this portal):
 * a submenu shows only with its ACTIVE cell. Unreadable access → no menu.
 *
 * Builder preview (LOCAL DEV ONLY, ./preview.ts): a tab opened by the builder draws the
 * builder's current layout instead. Compiled out of production builds.
 */
import { useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { apiGet } from '../shared/web-core/api';
import { FullscreenSpinner } from '../shared/web-core/guards';
import { useSession } from '../shared/web-core/session-core';
import { MenuAccessUnavailable } from './MenuAccessUnavailable';
import { NoMenuConfigured } from './NoMenuConfigured';
import { fetchMenuAccess } from './menu-access';
import { MenusUnavailable } from './MenusUnavailable';
import { loadMenus } from './delivery';
import type { Delivery } from './delivery';
import { PreviewBanner, PreviewEmpty } from './PreviewBanner';
import { PREVIEW_ACCESS, previewActive, usePreviewLayout } from './preview';
import { MenuAccessCtx, SignedLayoutCtx } from './signed-layout';

export interface SignedMenuGateProps {
  portal: string;
  /** the portal's display name, for the "no menu configured" screen */
  label: string;
  children: ReactNode;
}

async function fetchMenus(portal: string): Promise<Delivery> {
  return loadMenus(portal, () => apiGet<unknown>(`/api/ui/menus/${encodeURIComponent(portal)}`));
}

export function SignedMenuGate(props: SignedMenuGateProps): ReactNode {
  const session = useSession();
  const authed = session.status === 'authed';
  // build-time constant first: production builds drop the whole preview branch
  const preview = import.meta.env.DEV && previewActive(props.portal);
  const previewLayout = usePreviewLayout(props.portal, preview);
  const { data, isPending, isError, refetch } = useQuery({
    // per user: a new sign-in always reloads, never reuses another identity's menus
    queryKey: ['ui', 'portal-menus', props.portal, session.user?.userId ?? null],
    queryFn: () => fetchMenus(props.portal),
    enabled: authed && !preview,
    staleTime: 60_000,
    retry: 1, // an idempotent read (ui-std §7)
  });
  const access = useQuery({
    queryKey: ['ui', 'menu-access', props.portal, session.user?.userId ?? null],
    queryFn: () => fetchMenuAccess(props.portal),
    enabled: authed && !preview && data?.kind === 'layout',
    staleTime: 60_000,
    retry: 1, // an idempotent read (ui-std §7)
  });

  if (session.status === 'loading') return <FullscreenSpinner label="Restoring session" />;
  if (!authed || data?.kind === 'reauth') return <Navigate to="/login" replace />;
  if (preview) {
    if (!previewLayout) return <PreviewEmpty label={props.label} />;
    return (
      <SignedLayoutCtx.Provider value={previewLayout}>
        <MenuAccessCtx.Provider value={PREVIEW_ACCESS}>
          {props.children}
          <PreviewBanner />
        </MenuAccessCtx.Provider>
      </SignedLayoutCtx.Provider>
    );
  }
  if (isError) {
    return <MenusUnavailable label={props.label} detail="The server didn’t answer. Check your connection and try again." onRetry={() => void refetch()} />;
  }
  if (isPending || !data) return <FullscreenSpinner label="Loading menus" />;
  if (data.kind === 'invalid') return <MenusUnavailable label={props.label} detail={data.reason} onRetry={() => void refetch()} />;
  // no published layout → no menu, said plainly (never a build-time fallback menu)
  if (data.kind === 'missing') return <NoMenuConfigured label={props.label} />;
  if (access.data?.kind === 'reauth') return <Navigate to="/login" replace />;
  if (access.isError) return <MenuAccessUnavailable label={props.label} onRetry={() => void access.refetch()} />;
  if (access.isPending || !access.data) return <FullscreenSpinner label="Loading your menu access" />;
  return (
    <SignedLayoutCtx.Provider value={data.layout}>
      <MenuAccessCtx.Provider value={access.data.access}>{props.children}</MenuAccessCtx.Provider>
    </SignedLayoutCtx.Provider>
  );
}
