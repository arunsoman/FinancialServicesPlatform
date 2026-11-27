/**
 * Gap probe — users list paging (user-management Phase A).
 *
 * Before the fix the widget called `users.list` with only `{ tenantId }`, so core returned its
 * first 25 users and the browser filtered/searched/counted that page: user #26+ was invisible and
 * the status counts were wrong. The list must ask the server for one page with search, status and
 * sort (AGENTS §2.7) and render the page envelope it gets back.
 */
import { describe, expect, it } from 'vitest';
import { SOURCES, buildPath } from '../../core/sources';
import { FIXTURES } from '../../fixtures';
import type { FixtureRequest } from '../../core/ports';
import { listParams, SORT_OPTIONS, STATUS_FILTERS } from './model';
import type { UserPage } from './model';
import usersOrganizationWidget from './index';

const T = '11111111-1111-1111-1111-111111111111';

describe('users.list is a server-side page', () => {
  it('lets page / size / q / status / sort leave the browser', () => {
    expect(SOURCES['users.list']['*'].query).toEqual(expect.arrayContaining(['page', 'size', 'q', 'status', 'sort']));
  });

  it('sends the page the user is on, with the search pushed to the server', () => {
    const params = listParams(T, { page: 1, size: 20, q: '  anna ', status: 'ACTIVE', sort: 'fullName,desc' });
    expect(buildPath(SOURCES['users.list']['*'], params)).toBe(
      `/api/tenants/${T}/users?page=1&size=20&q=anna&status=ACTIVE&sort=fullName%2Cdesc`,
    );
  });

  it('defaults to "current" (everything but removed) and drops an empty search', () => {
    const params = listParams(T, { page: 0, size: 20, q: '', status: 'current', sort: 'username,asc' });
    expect(params).toEqual({ tenantId: T, page: '0', size: '20', status: 'current', sort: 'username,asc' });
  });

  it('every sort option is one core accepts (its safelist)', () => {
    const allowed = new Set(['username', 'fullName', 'status', 'createdAt', 'updatedAt']);
    for (const o of SORT_OPTIONS) {
      const [field, dir] = o.id.split(',');
      expect(allowed.has(field), o.id).toBe(true);
      expect(['asc', 'desc']).toContain(dir);
    }
  });

  it('every status chip maps to a server count', () => {
    const page: UserPage = {
      items: [], page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false, sort: 'username,asc',
      counts: { CURRENT: 0, PENDING: 0, ACTIVE: 0, DISABLED: 0, ARCHIVED: 0 },
      org: { id: T, legalName: 'Acme', type: 'TENANT', subType: null, depth: 2 },
    };
    for (const f of STATUS_FILTERS) expect(page.counts[f.countKey], f.id).toBe(0);
  });

  it('can load one user by id (a deep link to a user who is not on the current page)', () => {
    expect(buildPath(SOURCES['users.get']['*'], { tenantId: T, personId: 'p1' })).toBe(`/api/tenants/${T}/users/p1`);
  });
});

describe('users.list fixture plays the paged server', () => {
  const call = (params: Record<string, string>) =>
    (FIXTURES['users.list'].default as (r: FixtureRequest) => UserPage)({
      params, body: undefined, permissions: [], scenario: 'default',
    });

  it('returns an envelope with totals and counts', () => {
    const p = call({ tenantId: T, page: '0', size: '2', status: 'current', sort: 'username,asc' });
    expect(p.items).toHaveLength(2);
    expect(p.totalElements).toBe(5);
    expect(p.hasNext).toBe(true);
    expect(p.counts.ARCHIVED).toBe(1);
  });

  it('searches and filters', () => {
    expect(call({ tenantId: T, q: 'maria' }).items.map((u) => u.username)).toEqual(['maria.nghipondoka']);
    expect(call({ tenantId: T, status: 'ARCHIVED' }).items.map((u) => u.username)).toEqual(['old.account']);
  });
});

describe('one org per users page — other orgs are reached by acting as them', () => {
  it('no longer fans out over /children to build an org switcher (N+1, unbounded)', () => {
    expect(usersOrganizationWidget.sources).not.toContain('users.org.children');
    expect(usersOrganizationWidget.sources).not.toContain('users.org');
    expect(SOURCES['users.org.children']).toBeUndefined();
  });

  it('the page names the org it lists (own org, or the one being acted as)', () => {
    const call = FIXTURES['users.list'].default as (r: FixtureRequest) => UserPage;
    const p = call({ params: { tenantId: T }, body: undefined, permissions: [], scenario: 'default' });
    expect(p.org.legalName).toBeTruthy();
  });
});
