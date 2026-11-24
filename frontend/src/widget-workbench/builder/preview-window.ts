/**
 * "Preview" in the portal builder: open the REAL portal in a new browser window sized to a
 * screen type. That window draws this builder's current layout (portals/signed/preview.ts,
 * dev only) and redraws on every edit made here.
 */
import { ADMIN_PORTAL, PORTAL_DISTRIBUTOR, PORTAL_MANUFACTURER, PORTAL_MERCHANT, PORTAL_WHOLESALER } from '../../portals/portal-config';

export interface ScreenType {
  id: 'desktop' | 'tablet' | 'mobile';
  label: string;
  width: number;
  height: number;
}

/** One per grid band a portal is designed for (lg / md / xs). */
export const SCREEN_TYPES: readonly ScreenType[] = [
  { id: 'desktop', label: 'Desktop', width: 1440, height: 900 },
  { id: 'tablet', label: 'Tablet', width: 820, height: 1180 },
  { id: 'mobile', label: 'Mobile', width: 390, height: 844 },
];

const PORTALS = [ADMIN_PORTAL, PORTAL_MANUFACTURER, PORTAL_DISTRIBUTOR, PORTAL_WHOLESALER, PORTAL_MERCHANT];

/** The portal's own entry, in preview mode. */
export function previewUrl(portalKey: string, origin: string): string {
  const base = PORTALS.find((p) => p.deliveryKey === portalKey)?.basePath ?? `/${portalKey}`;
  return `${origin}${base}/?preview=1`;
}

/** A named window per portal + screen type: clicking again re-focuses it instead of stacking new ones. */
export function openPreview(portalKey: string, type: ScreenType): void {
  window.open(
    previewUrl(portalKey, window.location.origin),
    `fsp-preview-${portalKey}-${type.id}`,
    `popup=yes,width=${type.width},height=${type.height}`,
  );
}
