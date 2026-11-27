/**
 * Keeps the builder and the server draft in step, so what a developer PREVIEWS is exactly
 * what the menu-publisher skill exports (scripts/export-portal-menus.sh takes each portal's
 * newest server layout). Pure helpers here; BuilderPage wires them.
 */
import { parseLayout } from './layout';
import type { PortalLayout } from './layout';
import type { LayoutView } from './server-layouts';

/** The newest of the server's draft and published layout (what the export would take). */
export function newestServerLayout(
  portal: string,
  draft: LayoutView | null | undefined,
  published: LayoutView | null | undefined,
): PortalLayout | null {
  const views = [draft, published].filter((v): v is LayoutView => !!v);
  views.sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt));
  for (const v of views) {
    const l = parseLayout(v.layoutJson, portal);
    if (l) return l;
  }
  return null;
}

/** Same layout regardless of key order or formatting (the server normalises what it stores). */
export function sameLayout(a: PortalLayout, b: PortalLayout): boolean {
  const canon = (v: unknown): unknown =>
    Array.isArray(v)
      ? v.map(canon)
      : v && typeof v === 'object'
        ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, canon((v as Record<string, unknown>)[k])]))
        : v;
  return JSON.stringify(canon(a)) === JSON.stringify(canon(b));
}

export type AutosaveState =
  | { kind: 'off'; why: string }
  | { kind: 'pending' }
  | { kind: 'saving' }
  | { kind: 'saved' }
  | { kind: 'failed'; text: string };

/** What the top bar says about the server copy. */
export function autosaveLabel(s: AutosaveState): string {
  switch (s.kind) {
    case 'off':
      return s.why;
    case 'pending':
      return 'Unsaved changes…';
    case 'saving':
      return 'Saving to server…';
    case 'saved':
      return 'Saved to server';
    case 'failed':
      return `Not saved: ${s.text}`;
  }
}
