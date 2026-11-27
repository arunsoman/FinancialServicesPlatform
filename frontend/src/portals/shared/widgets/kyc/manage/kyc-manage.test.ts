/**
 * kyc.manage: URL list state is closed-set (unknowns never reach the server), the fixture
 * server honours the plugin's list contract (totals-free paging, search, sort, filters), and
 * four-eyes / permission keys behave as the real plugin + core do.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { ApiError } from '../../../web-core/api';
import { FixtureDataPort } from '../../core/ports';
import { buildPath, resolveSource } from '../../core/sources';
import { FIXTURE_ME, KYC_MANAGE_FIXTURES, resetKycFixtures } from './fixture-server';
import { listParams, majorToMinor, minorToMajor, parseListQuery, validateStep, emptyForm } from './model';
import type { CaseSummary, CaseView, Page } from './model';

const ALL = ['kyc.review', 'kyc.approve', 'kyc.reject', 'org.onboard_child'];
const port = (perms = ALL) => new FixtureDataPort(KYC_MANAGE_FIXTURES, 'default', undefined, 0, perms);
const page = (p: FixtureDataPort, params: Record<string, string>) => p.request<Page<CaseSummary>>('kyc.cases.page', params);

beforeEach(() => resetKycFixtures());

describe('kyc.manage list state', () => {
  it('drops unknown statuses / sorts / sizes from the URL', () => {
    const q = parseListQuery(new URLSearchParams('kstatus=APPROVED,BOGUS&ksort=password,asc&ksize=7&kpage=-2&krisk=HIGH'));
    expect(q).toMatchObject({ status: ['APPROVED'], risk: ['HIGH'], sort: 'submittedAt', size: 25, page: 0 });
    expect(listParams({ ...q, q: 'a' })).not.toHaveProperty('q'); // 1 char is never sent
  });

  it('only allow-listed query params leave the browser', () => {
    const req = resolveSource('kyc.cases.page', 'ANY')!;
    expect(buildPath(req, { page: '1', size: '10', q: 'namib', evil: 'x', tenantId: 't' }))
      .toBe('/api/plugins/kyc/cases?page=1&size=10&q=namib');
  });

  it('keeps money as minor-unit strings', () => {
    expect(majorToMinor('1250.5')).toBe('125050');
    expect(minorToMajor('125050')).toBe('1250.50');
  });

  it('validates a step against NamibiaRules mirrors', () => {
    const e = validateStep('company', { ...emptyForm(), taxId: '123', registrationNumber: 'x' });
    expect(e.taxId).toBe('TIN is 8 digits');
    expect(e.registrationNumber).toBeDefined();
  });
});

describe('kyc fixture server (plugin contract)', () => {
  it('pages without totals and says when there is a next page', async () => {
    const p = port();
    const first = await page(p, { page: '0', size: '10', sort: 'legalName,asc' });
    expect(first.items).toHaveLength(10);
    expect(first.hasNext).toBe(true);
    expect(first).not.toHaveProperty('totalItems');
    const names = first.items.map((i) => i.legalName.toLowerCase());
    expect([...names].sort()).toEqual(names);
    const last = await page(p, { page: '4', size: '10', sort: 'legalName,asc' });
    expect(last.hasNext).toBe(false);
  });

  it('searches and filters on the server', async () => {
    const hits = await page(port(), { page: '0', size: '25', q: 'coffee', sort: 'submittedAt,desc' });
    expect(hits.items.map((i) => i.legalName)).toEqual([expect.stringContaining('Coffee')]);
    const approved = await page(port(), { page: '0', size: '100', status: 'APPROVED', sort: 'submittedAt,desc' });
    expect(approved.items.every((i) => i.status === 'APPROVED')).toBe(true);
    await expect(page(port(), { page: '0', size: '25', sort: 'secret,asc' })).rejects.toBeInstanceOf(ApiError);
  });

  it('never lets the maker decide; reject needs kyc.reject', async () => {
    const p = port();
    const all = await page(p, { page: '0', size: '100', status: 'PENDING_REVIEW', sort: 'submittedAt,desc' });
    const views = await Promise.all(all.items.map((i) => p.request<CaseView>('kyc.case.view', { caseId: i.caseId })));
    const mine = views.find((v) => v.makerName === FIXTURE_ME.name)!;
    const theirs = views.find((v) => v.makerName !== FIXTURE_ME.name)!;
    expect(mine.canDecide).toBe(false);
    expect(theirs.canDecide).toBe(true);
    await expect(
      p.command('kyc.case.decide', { decision: 'APPROVE', reason: null, expectedVersion: mine.version }, { caseId: mine.caseId }),
    ).rejects.toMatchObject({ status: 403, code: 'MAKER_CANNOT_APPROVE' });
    const noReject = port(['kyc.review', 'kyc.approve']);
    await expect(
      noReject.command('kyc.case.decide', { decision: 'REJECT', reason: 'Not verifiable at BIPA', expectedVersion: theirs.version }, { caseId: theirs.caseId }),
    ).rejects.toMatchObject({ status: 403 });
    const ok = await p.command<CaseView>('kyc.case.decide', { decision: 'APPROVE', reason: null, expectedVersion: theirs.version }, { caseId: theirs.caseId });
    expect(ok.status).toBe('APPROVED');
    await expect(
      p.command('kyc.case.decide', { decision: 'APPROVE', reason: null, expectedVersion: theirs.version }, { caseId: theirs.caseId }),
    ).rejects.toMatchObject({ status: 409 });
  });

  it('answers 503 PLUGIN_NOT_READY in the not-ready scenario', async () => {
    const p = new FixtureDataPort(KYC_MANAGE_FIXTURES, 'not-ready', undefined, 0, ALL);
    await expect(page(p, { page: '0', size: '25' })).rejects.toMatchObject({ status: 503, code: 'PLUGIN_NOT_READY' });
  });
});

describe('an org onboards its CHILD level only — never a sibling', () => {
  it('a new application offers just the child level, and nothing while it is unknown', async () => {
    const { subTypeOptions } = await import('./model');
    // a distributor's child is a wholesaler: no DISTRIBUTOR (sibling), no MANUFACTURER (above)
    expect(subTypeOptions('create', 'WHOLESALER', '').map((o) => o.value)).toEqual(['WHOLESALER']);
    expect(subTypeOptions('create', undefined, '')).toEqual([]);
    expect(subTypeOptions('edit', undefined, 'DISTRIBUTOR').map((o) => o.value)).toEqual(['DISTRIBUTOR']);
  });
});
