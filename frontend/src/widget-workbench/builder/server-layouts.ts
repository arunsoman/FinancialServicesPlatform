/**
 * The builder's server side: core's layout records for one portal.
 *   GET /v1/ui/layouts/{portal}/draft | published   (404 = there is none)
 *   PUT /v1/ui/layouts/{portal}/draft               autosave (ui.layout.draft.save)
 * Shipping is not done here: the menu-publisher skill exports the newest layout into the repo
 * seed and CI/CD deploys it (docs/portal-menus.md). All through the BFF lane (`/api/**`); the
 * save carries an Idempotency-Key (api.ts) and is never retried automatically (ui-std §7).
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, apiGet, apiPut } from '../../portals/shared/web-core/api';
import type { PortalLayout } from './layout';

export interface LayoutView {
  id: string;
  portal: string;
  status: string;
  layoutJson: string;
  schemaVersion: number;
  makerId: string;
  updatedAt: string;
  versionNum: number | null;
}

const keys = {
  all: (portal: string) => ['ui', 'layouts', portal] as const,
  draft: (portal: string) => ['ui', 'layouts', portal, 'draft'] as const,
  published: (portal: string) => ['ui', 'layouts', portal, 'published'] as const,
};

/** 404 = "there is none" for these reads. */
async function orNull<T>(p: Promise<T>): Promise<T | null> {
  try {
    return await p;
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  }
}

export function useServerLayouts(portal: string, enabled: boolean) {
  const draft = useQuery({
    queryKey: keys.draft(portal),
    queryFn: () => orNull(apiGet<LayoutView>(`/api/ui/layouts/${encodeURIComponent(portal)}/draft`)),
    enabled,
  });
  const published = useQuery({
    queryKey: keys.published(portal),
    queryFn: () => orNull(apiGet<LayoutView>(`/api/ui/layouts/${encodeURIComponent(portal)}/published`)),
    enabled,
  });
  return { draft, published };
}

/** Autosave: the editor's layout becomes the portal's server draft. */
export function useSaveDraft(portal: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (layout: PortalLayout) =>
      apiPut<LayoutView>(`/api/ui/layouts/${encodeURIComponent(portal)}/draft`, { layoutJson: JSON.stringify(layout), schemaVersion: 1 }),
    retry: 0,
    onSettled: () => qc.invalidateQueries({ queryKey: keys.all(portal) }),
  });
}
