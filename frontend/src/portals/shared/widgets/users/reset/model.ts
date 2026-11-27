/**
 * users.reset — which credential action each user gets, mirroring core's rules
 * (UserAdminService.passwordResetRequest / requireAwaitingActivation). Core still decides.
 *
 * A reset is four-eyes: the request opens a USER_PASSWORD_RESET approval; once a DIFFERENT
 * person approves it, the IdP emails a one-time reset link (links never go by SMS). Nobody —
 * not the admin, not the checker — ever sees or sets the password.
 */
import type { UserRow } from '../organization/model';

export type CredentialAction =
  | { kind: 'reset' }
  | { kind: 'resend' }
  | { kind: 'none'; reason: string };

export function credentialAction(u: UserRow, meId: string | null): CredentialAction {
  if (u.id === meId) return { kind: 'none', reason: 'Your own account — ask another administrator (four-eyes)' };
  if (u.status === 'ARCHIVED') return { kind: 'none', reason: 'Removed — can’t sign in' };
  if (u.status === 'PENDING') return { kind: 'none', reason: 'Awaiting approval' };
  if (u.status === 'DISABLED') return { kind: 'none', reason: 'Deactivated — reactivate first' };
  // approved but never set a password: the activation link/code IS their set-password step
  if (u.activated === false) return { kind: 'resend' };
  if (!u.email) return { kind: 'none', reason: 'No email on file — reset links go by email' };
  return { kind: 'reset' };
}

/** j•••@example.com — enough to recognise, not to harvest. */
export function maskEmail(e: string | null | undefined): string {
  if (!e) return '—';
  const at = e.indexOf('@');
  return at <= 1 ? e : `${e[0]}•••${e.slice(at)}`;
}
