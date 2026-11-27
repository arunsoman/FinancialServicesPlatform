/**
 * onboard.list: parseOrgQuery drops unknowns, orgListParams only emits allow-listed
 * params, and the fixture server pages/searches/sorts on the server (never in the browser).
 */
import { describe, expect, it } from 'vitest';
import { FixtureDataPort } from '../../core/ports';
import { buildPath, resolveSource } from '../../core/sources';
import { parseOrgQuery, orgListParams, DEFAULT_QUERY } from './model';
import { fixtures } from './data';

import type { OrgRow } from './model';

const port = (perms = ['org.create']) => new FixtureDataPort(fixtures, 'default', undefined, 0, perms);

function pRequest(params: Record<string, string>) {
  return port().request<{ items: OrgRow[]; hasNext: boolean; page: number; size: number }>(
    'onboard.orgs.list',
    { tenantId: 't', ...params },
  );
}

describe('onboard.list query parsing', () => {
  it('drops unknown sizes/pages from the URL', () => {
    const q = parseOrgQuery(new URLSearchParams('osort=legalName,desc&osize=7&opage=-2&oq=namib&evil=x'));
    expect(q).toMatchObject({ sort: 'legalName', dir: 'desc', size: 25, page: 0, q: 'namib' });
  });

  it('only allow-listed query params leave the browser', () => {
    const req = resolveSource('onboard.orgs.list', 'ANY')!;
    expect(buildPath(req, { page: '1', size: '10', q: 'swipe', sort: 'legalName,asc', evil: 'x', tenantId: 't' }))
      .toBe('/api/tenants/t/children/page?page=1&size=10&q=swipe&sort=legalName%2Casc');
  });

  it('orgListParams emits only page/size/q/sort (q only when >= 2 chars)', () => {
    expect(orgListParams({ ...DEFAULT_QUERY, q: 'a' })).toEqual({ page: '0', size: '25', sort: 'legalName,asc' });
    expect(orgListParams({ ...DEFAULT_QUERY, q: 'swipe' })).toMatchObject({ q: 'swipe' });
  });
});

describe('onboard.list fixture server (server-side contract)', () => {
  it('pages: first page size 10, no totals, hasNext false (only 10 fixtures)', async () => {
    const first = await pRequest({ page: '0', size: '10', sort: 'legalName,asc' });
    expect(first.items).toHaveLength(10);
    expect(first.hasNext).toBe(false);
    expect(first.page).toBe(0);
    expect(first.size).toBe(10);
  });

  it('searches on the server (2+ chars)', async () => {
    const hits = await pRequest({ page: '0', size: '100', q: 'swipe', sort: 'legalName,asc' });
    expect(hits.items.map((i) => i.legalName)).toEqual([expect.stringContaining('Swipe')]);
    const none = await pRequest({ page: '0', size: '100', q: 'zzznotfound', sort: 'legalName,asc' });
    expect(none.items).toHaveLength(0);
  });

  it('sorts on the server', async () => {
    const asc = await pRequest({ page: '0', size: '100', sort: 'legalName,asc' });
    const names = asc.items.map((i) => i.legalName);
    expect(names).toEqual([...names].sort());
    const desc = await pRequest({ page: '0', size: '100', sort: 'legalName,desc' });
    expect(desc.items.map((i) => i.legalName)).toEqual([...names].sort().reverse());
  });

  it('403s without org.create', async () => {
    const noPerm = port([]);
    await expect(
      noPerm.request<{ items: OrgRow[] }>('onboard.orgs.list', { tenantId: 't', page: '0', size: '10' }),
    ).rejects.toMatchObject({ status: 403 });
  });
});
