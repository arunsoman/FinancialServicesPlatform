/**
 * The signed-in person's access to a portal's PUBLISHED menu (core GET /api/me/menu-access):
 * the Permissions Matrix cells their roles hold there. Parsed strictly — an answer that does not
 * match fails closed (no menu), never "everything".
 */
import * as z from 'zod';
import { ApiError, apiGet } from '../shared/web-core/api';
import type { MenuAccess } from './signed-layout';

const CELL = z.string().regex(/^[a-z][a-z0-9-]{0,47}\/[a-z][a-z0-9-]{0,47}:(CREATE|VIEW|UPDATE|DELETE|AUTHORIZE|ACTIVE)$/);

export const menuAccessSchema = z.object({
  portal: z.string().min(1),
  full: z.boolean(),
  cells: z.array(CELL),
});

export type MenuAccessLoad = { kind: 'ok'; access: MenuAccess } | { kind: 'reauth' };

export function parseMenuAccess(portal: string, raw: unknown): MenuAccess {
  const r = menuAccessSchema.safeParse(raw);
  if (!r.success || r.data.portal !== portal) throw new Error('Menu access answer did not match this portal');
  return { full: r.data.full, cells: new Set(r.data.cells) };
}

export async function fetchMenuAccess(portal: string): Promise<MenuAccessLoad> {
  try {
    const raw = await apiGet<unknown>(`/api/me/menu-access?portal=${encodeURIComponent(portal)}`);
    return { kind: 'ok', access: parseMenuAccess(portal, raw) };
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) return { kind: 'reauth' };
    throw e;
  }
}
