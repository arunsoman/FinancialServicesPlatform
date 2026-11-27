/**
 * Permission gate for widget fixtures — the workbench's fixture lane plays the SERVER's
 * permission check, so a frame without the cell gets the same 403 the gateway→core would
 * hand back (fsp-widget skill rule 8: `forbidden` = fail 403).
 */
import { ApiError } from '../../web-core/api';

export function forbidUnless(permissions: readonly string[], ...cells: string[]): void {
  if (!cells.every((c) => permissions.includes(c))) {
    throw new ApiError({
      status: 403,
      code: 'AUTH_FORBIDDEN',
      title: 'Forbidden',
      detail: `This action needs ${cells.join(' + ')}.`,
    });
  }
}
