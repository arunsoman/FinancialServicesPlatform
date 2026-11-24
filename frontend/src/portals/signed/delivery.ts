/**
 * ONE load-then-parse path for a portal's published menus (core GET /api/ui/menus/{portal}) —
 * shared by the boot gate (SignedMenuGate: this portal's sidebar) and every surface that reads
 * ANOTHER portal's published menu (rbac.matrix: the rows of a role's permissions). The fetch is
 * injected so each caller keeps its own transport (same-origin /api via the BFF — never a token
 * in the browser). The payload is shape-checked (zod) and bound to the portal that asked.
 *
 * Menus only steer navigation; core checks every API call a widget makes.
 */
import { signedLayoutSchema } from './signed-layout';
import type { SignedLayout } from './signed-layout';

export type Delivery =
  | { kind: 'layout'; layout: SignedLayout }
  | { kind: 'missing' }
  | { kind: 'reauth' }
  | { kind: 'invalid'; reason: string };

/** Network and server errors other than 401/404 are thrown, so the caller can offer a retry. */
export async function loadMenus(portal: string, fetchMenus: () => Promise<unknown>): Promise<Delivery> {
  let raw: unknown;
  try {
    raw = await fetchMenus();
  } catch (err) {
    const status = (err as { status?: number }).status;
    if (status === 401) return { kind: 'reauth' };
    if (status === 404) return { kind: 'missing' };
    throw err;
  }
  const parsed = signedLayoutSchema.safeParse(raw);
  if (!parsed.success) return { kind: 'invalid', reason: 'The published menus are not in a shape this version of the portal understands.' };
  if (parsed.data.portal !== portal) return { kind: 'invalid', reason: `The menus returned belong to the ${parsed.data.portal} portal.` };
  return { kind: 'layout', layout: parsed.data };
}
