/**
 * Builder preview (docs/portal-menus.md) — LOCAL DEV ONLY. The portal builder opens a portal
 * in a new window sized to a screen type with `?preview=1`; that window then draws the
 * builder's CURRENT layout (this browser's editor copy) with the real shell, real widgets
 * and real data, and redraws on every edit made in the builder.
 *
 * Nothing here exists in a production build: `import.meta.env.DEV` is a build-time constant,
 * so `previewActive` is `false` and the signed-delivery gate is the only path. Preview never
 * bypasses authentication: the window still needs a session and every API call is still
 * authorised by core. What it skips is the signature check — the layout comes from this
 * browser, so it was never signed — and the Permissions Matrix cells (full menu access), so a
 * new screen is visible before any role is granted it. Widget `requires` still gate rendering.
 */
import { useEffect, useState } from 'react';
import type { WidgetDef } from '../shared/widgets/core/manifest';
import { WIDGETS } from '../shared/widgets/registry';
import { signedLayoutSchema } from './signed-layout';
import type { MenuAccess, SignedLayout } from './signed-layout';

/** The builder's per-browser editor copy of a portal's layout (widget-workbench builder). */
export const builderDraftKey = (portal: string) => `fsp.widget-workbench.builder.${portal}`;

/** Which portal this browser TAB previews (survives the sign-in round trip, dies with the tab). */
const PREVIEW_FLAG = 'fsp.portal.preview';

/** Preview shows every menu entry; widgets' own `requires` still decide what renders. */
export const PREVIEW_ACCESS: MenuAccess = { full: true, cells: new Set() };

/** `?preview=1` turns this tab into a preview of `portal`, `?preview=off` turns it back. */
export function previewActive(portal: string): boolean {
  if (!import.meta.env.DEV) return false;
  try {
    const q = new URLSearchParams(window.location.search).get('preview');
    if (q === 'off') sessionStorage.removeItem(PREVIEW_FLAG);
    else if (q !== null) sessionStorage.setItem(PREVIEW_FLAG, portal);
    return sessionStorage.getItem(PREVIEW_FLAG) === portal;
  } catch {
    return false;
  }
}

/**
 * The builder's editor copy as the renderer's layout shape. Every widget this build has is
 * "approved" (nothing is signed locally); menus without submenus are dropped, as the portal
 * never shows them. null = nothing to preview, or the copy does not have a renderable shape
 * (the builder lists the errors).
 */
export function previewLayoutFrom(raw: string | null, portal: string, registry: readonly WidgetDef[]): SignedLayout | null {
  if (!raw) return null;
  let l: unknown;
  try {
    l = JSON.parse(raw);
  } catch {
    return null;
  }
  const draft = l as { portal?: unknown; menus?: unknown };
  if (!draft || draft.portal !== portal || !Array.isArray(draft.menus)) return null;
  const parsed = signedLayoutSchema.safeParse({
    portal,
    schemaVersion: 1,
    layoutVersion: 'preview',
    publishedAt: new Date().toISOString(),
    menus: (draft.menus as { screens?: unknown[] }[]).filter((m) => Array.isArray(m.screens) && m.screens.length > 0),
    widgets: registry.map((d) => ({ id: d.id, version: d.version, bundle: '', bundleHash: 'sha256:preview', requires: [] })),
  });
  return parsed.success ? parsed.data : null;
}

function readPreview(portal: string): SignedLayout | null {
  try {
    return previewLayoutFrom(localStorage.getItem(builderDraftKey(portal)), portal, WIDGETS);
  } catch {
    return null;
  }
}

/** The previewed layout, redrawn whenever the builder (another window) saves an edit. */
export function usePreviewLayout(portal: string, enabled: boolean): SignedLayout | null {
  const [layout, setLayout] = useState<SignedLayout | null>(() => (enabled ? readPreview(portal) : null));
  useEffect(() => {
    if (!enabled) return undefined;
    setLayout(readPreview(portal));
    const onStorage = (e: StorageEvent) => {
      if (e.key === builderDraftKey(portal)) setLayout(readPreview(portal));
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [portal, enabled]);
  return layout;
}
