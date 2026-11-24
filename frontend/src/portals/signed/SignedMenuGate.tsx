/**
 * Verify-then-render boot gate (plans/signed-portal-delivery-plan.md §3). Fetches the
 * portal's signed delivery bundle, verifies it OFFLINE against the build-pinned trust
 * store, parses the verified payload, and only then renders children — with the layout
 * on SignedLayoutCtx for the shell + layout screens. Any tamper evidence renders the
 * fail-closed TamperedBoundary — never a degraded menu, never a cached layout.
 *
 * Policy: a portal's menu comes ONLY from its published layout. A missing bundle renders
 * "No menu configured" for every portal — fail closed, never the build-time menu registry
 * (so a withheld delivery cannot downgrade a portal to a coded menu either).
 *
 * Authentication comes first: bundle delivery is an authenticated read, so the gate only
 * verifies once a session exists. No session, or a 401 (token expired/revoked), forces
 * re-authentication through the hosted IdP sign-in (FAPI2 error-UX map: "invalid token →
 * force re-authentication") — never a tamper verdict, and nothing renders in between.
 *
 * Then the person's menu access (the Permissions Matrix cells their roles hold on this portal):
 * a submenu shows only with its ACTIVE cell. Unreadable access → no menu (fail closed).
 *
 * Builder preview (LOCAL DEV ONLY, ./preview.ts): a tab opened by the builder draws the
 * builder's current, unsigned layout instead. Compiled out of production builds.
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
import { TamperedBoundary } from './TamperedBoundary';
import { verifyDelivery } from './delivery';
import type { Delivery } from './delivery';
import { PreviewBanner, PreviewEmpty } from './PreviewBanner';
import { PREVIEW_ACCESS, previewActive, usePreviewLayout } from './preview';
import { MenuAccessCtx, SignedLayoutCtx } from './signed-layout';
import { reasonLabel } from './verify';
import type { SignedBundle } from './verify';

export interface SignedMenuGateProps {
  portal: string;
  /** the portal's display name, for the "no menu configured" screen */
  label: string;
  children: ReactNode;
}

async function fetchVerified(portal: string): Promise<Delivery> {
  return verifyDelivery(portal, () => apiGet<SignedBundle>(`/api/ui/bundles/${encodeURIComponent(portal)}`));
}

export function SignedMenuGate(props: SignedMenuGateProps): ReactNode {
  const session = useSession();
  const authed = session.status === 'authed';
  // build-time constant first: production builds drop the whole preview branch
  const preview = import.meta.env.DEV && previewActive(props.portal);
  const previewLayout = usePreviewLayout(props.portal, preview);
  const { data, isPending } = useQuery({
    // per user: a new sign-in always re-verifies, never reuses another identity's verdict
    queryKey: ['ui', 'signed-delivery', props.portal, session.user?.userId ?? null],
    queryFn: () => fetchVerified(props.portal),
    enabled: authed && !preview,
    staleTime: 60_000,
    retry: 0, // money-grade rule (ui-std §7): zero auto-retries — the outcome is surfaced
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
  if (isPending || !data) return <FullscreenSpinner label="Verifying signed delivery" />;

  if (data.kind === 'closed') {
    return <TamperedBoundary portal={props.portal} reason={reasonLabel(data.reason)} evidenceId={data.evidenceId} />;
  }
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
