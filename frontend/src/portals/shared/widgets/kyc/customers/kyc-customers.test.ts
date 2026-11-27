/**
 * kyc.customers: the registration never carries a password, the primary contact decides the
 * required field, the list is pinned to kind=CUSTOMER with allow-listed params only, and the
 * fixture server plays the server rules the UI relies on — contact uniqueness, four-eyes,
 * and "activate only after the customer activated".
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { ApiError } from '../../../web-core/api';
import { FixtureDataPort } from '../../core/ports';
import { buildPath, resolveCommand, resolveSource } from '../../core/sources';
import { validateStep, emptyForm, toWire } from '../manage/model';
import { fixtures, resetCustomerFixtures } from './data';
import { customerToWire, emptyCustomer, parseQuery, queryParams, stageOf, validateCustomer } from './model';
import type { CustomerCaseView } from './model';

const ALL = ['kyc.review', 'kyc.approve', 'kyc.reject', 'org.onboard_child'];
const port = (perms = ALL) => new FixtureDataPort(fixtures, 'default', undefined, 0, perms);
const fail = async (p: Promise<unknown>) => {
  try { await p; } catch (e) { return e as ApiError; }
  throw new Error('expected a refusal');
};
const HOME = { line: '12 Robert Mugabe Ave', city: 'Windhoek', region: 'KHOMAS', postalCode: '' };
const valid = { ...emptyCustomer(), firstName: 'John', lastName: 'Doe', gender: 'MALE', dateOfBirth: '1990-01-01', email: 'john@example.com', residentialAddress: HOME };

beforeEach(() => resetCustomerFixtures());

describe('customer registration form', () => {
  it('never sends a password — the customer sets it from the activation', () => {
    const wire = customerToWire({ ...valid, phone: '+264 81 123 4567' }) as Record<string, unknown>;
    expect(Object.keys(wire).some((k) => /pass/i.test(k))).toBe(false);
    expect(wire).toMatchObject({ email: 'john@example.com', phone: '+264811234567', primaryContact: 'EMAIL' });
  });

  it('requires the field of the chosen primary contact', () => {
    expect(validateCustomer({ ...valid, email: '' }).email).toMatch(/activation link/);
    const phoneFirst = validateCustomer({ ...valid, primaryContact: 'PHONE' });
    expect(phoneFirst.phone).toMatch(/SMS/);
    expect(validateCustomer({ ...valid, primaryContact: 'PHONE', phone: '081 123' }).phone).toMatch(/international/);
    expect(validateCustomer(valid)).toEqual({});
  });

  it('requires a residential address, like an organization\'s registered address', () => {
    const e = validateCustomer({ ...valid, residentialAddress: { line: '', city: '', region: '', postalCode: '' } });
    expect(e['residentialAddress.line']).toMatch(/residential street address/);
    expect(e['residentialAddress.city']).toBeDefined();
    expect(e['residentialAddress.region']).toBe('Choose a region');
  });

  it('a different postal address is validated and sent only when it differs', () => {
    const differs = { ...valid, postalSameAsResidential: false };
    expect(validateCustomer(differs)['postalAddress.line']).toMatch(/postal street address/);
    const same = customerToWire(valid) as Record<string, unknown>;
    expect(same).toMatchObject({ residentialAddress: { line: '12 Robert Mugabe Ave', city: 'Windhoek', region: 'KHOMAS', postalCode: null }, postalSameAsResidential: true, postalAddress: null });
    const po = { ...differs, postalAddress: { line: 'PO Box 1234', city: 'Windhoek', region: 'KHOMAS', postalCode: '9000' } };
    expect(validateCustomer(po)).toEqual({});
    expect((customerToWire(po) as Record<string, unknown>).postalAddress).toEqual({ line: 'PO Box 1234', city: 'Windhoek', region: 'KHOMAS', postalCode: '9000' });
  });

  it('rejects missing personal details and a future birth date', () => {
    const e = validateCustomer({ ...emptyCustomer(), dateOfBirth: '2999-01-01', email: 'x@y.z' });
    expect(e).toMatchObject({ firstName: expect.any(String), lastName: expect.any(String), gender: 'Select a gender' });
    expect(e.dateOfBirth).toMatch(/past/);
  });
});

describe('customer list', () => {
  it('pins kind=CUSTOMER and sends only allow-listed params', () => {
    const q = parseQuery(new URLSearchParams('cstatus=BOGUS&csort=password,asc&csize=7'));
    expect(q).toMatchObject({ status: '', sort: 'submittedAt', size: 25 });
    const req = resolveSource('kyc.customers.page', 'ANY')!;
    expect(buildPath(req, { ...queryParams(q), evil: 'x' })).toBe('/api/plugins/kyc/cases?kind=CUSTOMER&page=0&size=25&sort=submittedAt%2Cdesc');
  });

  it('commands reach the plugin routes', () => {
    expect(resolveCommand('kyc.customers.create', 'ANY')?.path).toBe('/api/plugins/kyc/customers');
    expect(resolveCommand('kyc.customers.resend', 'ANY')?.path).toBe('/api/plugins/kyc/cases/{caseId}/activation');
  });

  it('stage follows status + activation', () => {
    expect(stageOf({ status: 'PENDING_REVIEW', contactVerified: false })).toBe('AWAITING_CUSTOMER');
    expect(stageOf({ status: 'PENDING_REVIEW', contactVerified: true })).toBe('AWAITING_CHECKER');
    expect(stageOf({ status: 'APPROVED' })).toBe('ACTIVE');
  });
});

describe('server rules the widget relies on (fixture server)', () => {
  it('"Email is already registered." on a duplicate contact', async () => {
    const e = await fail(port().command('kyc.customers.create', customerToWire({ ...valid, email: 'ndapewa@example.com' })));
    expect(e.status).toBe(409);
    expect(e.code).toBe('CUSTOMER_EMAIL_TAKEN');
  });

  it('a checker cannot activate before the customer set their password', async () => {
    const p = port();
    const v = await p.request<CustomerCaseView>('kyc.customers.view', { caseId: 'c0000000-0000-0000-0000-000000000001' });
    expect(v.customer?.contactVerified).toBe(false);
    const e = await fail(p.command('kyc.customers.decide', { decision: 'APPROVE', expectedVersion: v.version }, { caseId: v.caseId }));
    expect(e.code).toBe('CONTACT_NOT_VERIFIED');
  });

  it('the case carries the address the plugin stored (residential + postal)', async () => {
    const v = await port().request<CustomerCaseView>('kyc.customers.view', { caseId: 'c0000000-0000-0000-0000-000000000002' });
    expect(v).toMatchObject({ registeredAddress: '7 Sam Nujoma Ave', registeredRegion: 'OSHANA', operationalAddressSame: false, operationalAddress: 'PO Box 55' });
  });

  it('activates once the customer activated', async () => {
    const p = port();
    const v = await p.request<CustomerCaseView>('kyc.customers.view', { caseId: 'c0000000-0000-0000-0000-000000000002' });
    const done = await p.command<CustomerCaseView>('kyc.customers.decide', { decision: 'APPROVE', expectedVersion: v.version }, { caseId: v.caseId });
    expect(done.status).toBe('APPROVED');
  });

  it('the maker can never decide their own registration (four-eyes)', async () => {
    const p = port();
    const created = await p.command<CustomerCaseView>('kyc.customers.create', customerToWire(valid));
    expect(created.blockedReason).toBe('MAKER');
    const e = await fail(p.command('kyc.customers.decide', { decision: 'REJECT', expectedVersion: created.version }, { caseId: created.caseId }));
    expect(e.code).toBe('MAKER_CANNOT_APPROVE');
  });

  it('registration needs org.onboard_child', async () => {
    const e = await fail(port(['kyc.review']).command('kyc.customers.create', customerToWire(valid)));
    expect(e.status).toBe(403);
  });
});

describe('organization application: the administrator and checker activate by link/code', () => {
  it('the Sign-in step requires a primary contact for both', () => {
    const e = validateStep('access', emptyForm());
    expect(e['admin.fullName']).toBeDefined();
    expect(e['admin.email']).toMatch(/activation link/);
    expect(e['checker.email']).toMatch(/activation link/);
  });

  it('sends their contacts, never a password', () => {
    const f = emptyForm();
    f.admin = { fullName: 'Ada Admin', email: 'ADA@x.com', phone: '', primaryContact: 'EMAIL' };
    f.checker = { fullName: 'Carl Checker', email: '', phone: '0026481 111 2222', primaryContact: 'PHONE' };
    const w = toWire(f);
    expect(w.admin).toEqual({ fullName: 'Ada Admin', email: 'ada@x.com', phone: null, primaryContact: 'EMAIL' });
    expect(w.checker).toMatchObject({ phone: '+264811112222', primaryContact: 'PHONE' });
    expect(JSON.stringify(w)).not.toMatch(/password/i);
  });
});
