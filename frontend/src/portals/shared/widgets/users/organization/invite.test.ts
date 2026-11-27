/**
 * Gap probe — users are IdP-managed. Before: "Add user" created the person with the
 * environment's initial password, which the admin had to pass on. Now nobody sets a password:
 * the add carries a primary contact, the IdP sends the activation once a checker approves,
 * and an approved user who hasn't activated reads "Awaiting activation" with a re-send.
 */
import { describe, expect, it } from 'vitest';
import { COMMANDS } from '../../core/sources';
import { awaitingActivation, createUserBody, userCreateSchema } from './model';
import type { UserFormValues, UserRow } from './model';
import usersOrganizationWidget from './index';

const base: UserFormValues = {
  username: 'ada.admin', fullName: 'Ada Admin', email: '', phone: '', roleIds: [], checker: false, primaryContact: 'EMAIL',
};
const issues = (v: UserFormValues) => {
  const r = userCreateSchema.safeParse(v);
  return r.success ? {} : Object.fromEntries(r.error.issues.map((i) => [i.path.join('.'), i.message]));
};

describe('adding a user — no password, a primary contact instead', () => {
  it('requires the contact the activation goes to', () => {
    expect(issues(base).email).toMatch(/activation link/);
    expect(issues({ ...base, primaryContact: 'PHONE' }).phone).toMatch(/SMS/);
    expect(issues({ ...base, primaryContact: 'PHONE', phone: '081 123' }).phone).toMatch(/international/);
    expect(issues({ ...base, email: 'ada@x.com' })).toEqual({});
  });

  it('sends the contact (E.164) and the channel, never a password', () => {
    const body = createUserBody({ ...base, email: 'ADA@x.com', phone: '0026481 111 2222', primaryContact: 'PHONE' });
    expect(body).toMatchObject({ email: 'ada@x.com', phone: '+264811112222', primaryContact: 'PHONE' });
    expect(JSON.stringify(body)).not.toMatch(/password/i);
  });
});

describe('approved users who have not activated', () => {
  const u = (status: UserRow['status'], activated: boolean | null) => ({ status, activated });
  it('are "awaiting activation" only when approved and not yet activated', () => {
    expect(awaitingActivation(u('ACTIVE', false))).toBe(true);
    expect(awaitingActivation(u('ACTIVE', true))).toBe(false);
    expect(awaitingActivation(u('ACTIVE', null))).toBe(false); // legacy initial-password user
    expect(awaitingActivation(u('PENDING', false))).toBe(false); // still awaiting the checker
  });

  it('can be sent a new activation through core', () => {
    expect(COMMANDS['user.activation.resend']['*']).toEqual({ method: 'POST', path: '/api/tenants/{tenantId}/users/{personId}/activation' });
    expect(usersOrganizationWidget.commands).toContain('user.activation.resend');
  });
});
