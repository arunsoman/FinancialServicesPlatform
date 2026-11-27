/**
 * Gap probe — Reset Password was a "Nothing here yet" placeholder. Now each user gets exactly the
 * credential action core allows (UserAdminService.passwordResetRequest / requireAwaitingActivation):
 * a four-eyes reset (IdP emails a one-time link), a re-sent activation for someone who never set
 * a password, or nothing — with the reason — instead of a request that would silently do nothing.
 */
import { describe, expect, it } from 'vitest';
import { COMMANDS } from '../../core/sources';
import { menuFor } from '../../../web-core/menu';
import type { UserRow } from '../organization/model';
import { credentialAction, maskEmail } from './model';
import passwordResetWidget from './index';

const u = (patch: Partial<UserRow>): UserRow => ({
  id: 'u1', tenantId: 't', username: 'ada', fullName: 'Ada Admin', email: 'ada@x.com', phone: null, status: 'ACTIVE',
  isApplicant: false, checker: false, roles: [], version: 3, createdAt: '', updatedAt: '', archivedAt: null,
  primaryContact: 'EMAIL', activated: true, ...patch,
});

describe('which credential action a user gets', () => {
  it('an active, activated user with an email → a four-eyes reset request', () => {
    expect(credentialAction(u({}), 'me').kind).toBe('reset');
    expect(credentialAction(u({ primaryContact: null, activated: null }), 'me').kind).toBe('reset'); // legacy user
  });
  it('approved but never set a password → their activation again, not a reset', () => {
    expect(credentialAction(u({ activated: false }), 'me').kind).toBe('resend');
  });
  it('anything core would refuse shows the reason instead of a button', () => {
    expect(credentialAction(u({}), 'u1')).toMatchObject({ kind: 'none', reason: expect.stringMatching(/another administrator/) });
    expect(credentialAction(u({ status: 'PENDING' }), 'me')).toMatchObject({ kind: 'none' });
    expect(credentialAction(u({ status: 'DISABLED' }), 'me')).toMatchObject({ kind: 'none', reason: expect.stringMatching(/reactivate/) });
    expect(credentialAction(u({ email: null, primaryContact: 'PHONE' }), 'me')).toMatchObject({ kind: 'none', reason: expect.stringMatching(/email/) });
  });
  it('emails are masked on screen', () => {
    expect(maskEmail('ada@x.com')).toBe('a•••@x.com');
  });
});

describe('wiring', () => {
  it('the request goes to core\'s four-eyes reset endpoint', () => {
    expect(COMMANDS['user.password.reset.request']['*']).toEqual({ method: 'POST', path: '/api/tenants/{tenantId}/users/{personId}/password-reset-request' });
    expect(passwordResetWidget.commands).toEqual(['user.password.reset.request', 'user.activation.resend']);
  });
  it('Reset Password is a real screen on the admin console and every org portal', () => {
    expect(menuFor('platform').find((i) => i.label === 'Reset Password')?.widgets).toEqual(['users.reset']);
    expect(menuFor('org').find((i) => i.link === '/reset-password')?.group).toBe('User Management');
  });
});
