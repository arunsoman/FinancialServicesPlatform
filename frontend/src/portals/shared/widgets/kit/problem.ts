/**
 * One mapping from a failed call to what a widget shows: stable server `code` (RFC-7807
 * `code`, or the 403 title the BFF relays) + the human `detail`. Never echoes tokens/PII —
 * the server already guarantees that (FAPI2 error semantics).
 */
import { ApiError } from '../../web-core/api';

export interface Problem {
  status: number;
  code: string;
  message: string;
}

export function toProblem(err: unknown): Problem {
  if (err instanceof ApiError) {
    const code = err.code ?? (err.status === 403 ? err.title : `HTTP_${err.status}`);
    return { status: err.status, code, message: err.detail };
  }
  return { status: 0, code: 'NETWORK', message: err instanceof Error ? err.message : String(err) };
}

/** 401/403 → the caller is not allowed; everything else is a failure worth a retry button. */
export function isForbidden(err: unknown): boolean {
  return err instanceof ApiError && (err.status === 401 || err.status === 403);
}
