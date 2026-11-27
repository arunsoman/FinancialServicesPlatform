/**
 * Widget workbench shared vocabulary (SME projects/widget-workbench, FSP terms).
 */
import type { Tier } from '../portals/shared/widgets/core/grid';
import type { WidgetDef } from '../portals/shared/widgets/core/manifest';
import { requiresRows } from '../portals/shared/widgets/core/manifest';

/** The content width each tier's frame / canvas is drawn at (inside tierOf's band). */
export const TIER_TEST_WIDTH: Record<Tier, number> = { xs: 390, sm: 768, md: 1100, lg: 1440 };
export const TIER_LABEL: Record<Tier, string> = { xs: 'Phone', sm: 'Tablet', md: 'Laptop', lg: 'Desktop' };

/** Business portals a widget can be previewed in (WidgetDef.contexts minus ANY). */
export const PORTAL_CONTEXTS = ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'WHOLESALER', 'MERCHANT'] as const;
export type PortalContext = (typeof PORTAL_CONTEXTS)[number];

/** Builder targets: the portal key layouts are published under (core). */
export interface BuilderPortal {
  key: string;
  label: string;
  context: PortalContext;
  /** build-time menu registry portal (seed for "today's menus") */
  menuPortal: 'platform' | 'org';
}
export const BUILDER_PORTALS: readonly BuilderPortal[] = [
  { key: 'admin', label: 'Admin console', context: 'PLATFORM', menuPortal: 'platform' },
  { key: 'manufacturer', label: 'Manufacturer', context: 'MANUFACTURER', menuPortal: 'org' },
  { key: 'distributor', label: 'Distributor', context: 'DISTRIBUTOR', menuPortal: 'org' },
  { key: 'wholesaler', label: 'Wholesaler', context: 'WHOLESALER', menuPortal: 'org' },
  { key: 'merchant', label: 'Merchant', context: 'MERCHANT', menuPortal: 'org' },
];

/** Fixture org used when the URL names none and access is not the signed-in user's. */
export const FIXTURE_ORG = '00000000-0000-0000-0000-0000000000a1';

export function worksIn(def: WidgetDef, context: PortalContext): boolean {
  return def.contexts.includes('ANY') || def.contexts.includes(context);
}

export function defaultContext(def: WidgetDef): PortalContext {
  return (PORTAL_CONTEXTS.find((p) => def.contexts.includes(p)) ?? 'PLATFORM');
}

export function requiresText(def: WidgetDef): string {
  return (Array.isArray(def.requires) ? 'any of ' : 'all of ') + requiresRows(def).join(', ');
}

export function spansText(def: WidgetDef): string {
  return (['xs', 'sm', 'md', 'lg'] as const)
    .map((t) => {
      const s = def.size[t];
      return s.kind === 'hidden' ? '–' : `${s.min}-${s.default}-${s.max}`;
    })
    .join(' · ');
}

/** One-line JSON preview for the network table. */
export function preview(v: unknown, max = 90): string {
  const s = JSON.stringify(v ?? null) ?? 'null';
  return s.length > max ? `${s.slice(0, max)}…` : s;
}

export function groupByCategory(defs: readonly WidgetDef[]): { category: string; items: WidgetDef[] }[] {
  const by = new Map<string, WidgetDef[]>();
  for (const d of defs) {
    const c = d.category ?? 'Other';
    by.set(c, [...(by.get(c) ?? []), d]);
  }
  return [...by.entries()].map(([category, items]) => ({ category, items }));
}

/** core UiDtos.RegisterWidget — what `PUT /api/ui/widgets` (ui.widget.register) takes for a widget. */
export function registration(def: WidgetDef) {
  return {
    id: def.id,
    version: def.version,
    label: def.label,
    category: def.category ?? null,
    bundle: `widgets/${def.id.replace(/\./g, '-')}`,
    portals: BUILDER_PORTALS.filter((p) => worksIn(def, p.context)).map((p) => p.key),
    requires: requiresRows(def),
    bundleSha256: null,
  };
}

/** Registry ordering: as the build lists them, or by when core first registered (created) them. */
export type RegistryOrder = 'build' | 'created-desc' | 'created-asc';
export const REGISTRY_ORDERS: readonly { value: RegistryOrder; label: string }[] = [
  { value: 'build', label: 'Build order' },
  { value: 'created-desc', label: 'Newest created first' },
  { value: 'created-asc', label: 'Oldest created first' },
];

/**
 * Sorts rows by creation time (core's first registration, `createdAt`). A row core does not
 * hold yet has no creation time: it is the newest thing there is (just built, registration
 * pending), so it leads "newest first" and trails "oldest first". Ties keep id order.
 */
export function sortByCreated<T extends { id: string }>(
  rows: readonly T[], order: RegistryOrder, createdOf: (row: T) => string | null | undefined,
): T[] {
  if (order === 'build') return [...rows];
  const dir = order === 'created-desc' ? -1 : 1;
  const time = (r: T) => {
    const c = createdOf(r);
    const t = c ? Date.parse(c) : NaN;
    return Number.isNaN(t) ? null : t;
  };
  return [...rows].sort((a, b) => {
    const ta = time(a);
    const tb = time(b);
    if (ta === null || tb === null) {
      if (ta === tb) return a.id.localeCompare(b.id);
      // unregistered = newest: first when newest-first, last when oldest-first
      return (ta === null ? -1 : 1) * (dir === -1 ? 1 : -1);
    }
    return ta === tb ? a.id.localeCompare(b.id) : (ta - tb) * dir;
  });
}
