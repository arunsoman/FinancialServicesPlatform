/**
 * org.branches: the manifest and data mapping are coherent, URL state is closed-set, the
 * pure helpers say the right thing, request bodies are exactly what core receives, and the
 * fixture server behaves like core's four-eyes branch API.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { ApiError } from '../../../web-core/api';
import { FixtureDataPort } from '../../core/ports';
import { buildPath, resolveSource } from '../../core/sources';
import { commands, fixtures, sources } from './data';
import { BRANCH_FIXTURES, FIXTURE_ME, IN_USE_BRANCH_ID, resetBranchFixtures } from './fixture-server';
import orgBranchesWidget from './index';
import {
  approvedByText, blockedText, branchFormSchema, changeBlocker, createBody, decidedNotice, decisionBody, deleteParams,
  filedNotice, isSameName, listParams, MAKER_BLOCKED, nameError, NOT_CHECKER, problemText, readListQuery, statusView,
  updateBody,
} from './model';
import type { BranchChange, BranchPage, BranchRow, PendingChange } from './model';

const MANAGER = ['role.view', 'role.manage'];
const VIEWER = ['role.view'];
const port = (perms: string[] = MANAGER) => new FixtureDataPort(BRANCH_FIXTURES, 'default', undefined, 0, perms);
const list = (p: FixtureDataPort, params: Record<string, string> = {}) =>
  p.request<BranchPage>('org.branches.list', { page: '0', size: '100', status: 'CURRENT', sort: 'name,asc', ...params });
const byName = async (p: FixtureDataPort, name: string, status = 'CURRENT') =>
  (await list(p, { status, q: name })).items.find((b) => b.name === name)!;
const code = async (run: () => Promise<unknown>): Promise<string> => {
  try {
    await run();
    return 'NO_ERROR';
  } catch (e) {
    return e instanceof ApiError ? `${e.status} ${e.code ?? e.title}` : String(e);
  }
};

const row = (over: Partial<BranchRow> = {}): BranchRow => ({
  id: 'b1', code: 'BR-0001', name: 'Windhoek Head Office', status: 'ACTIVE', version: 3,
  makerId: 'm', makerName: 'Johannes Kapuka', createdAt: '2026-11-20T08:30:00Z',
  checkerId: 'c', checkerName: 'Peter Amutenya', decidedAt: '2026-11-20T10:05:00Z', decisionReason: null,
  updatedAt: '2026-11-20T10:05:00Z', pending: null, ...over,
});
const pending = (over: Partial<PendingChange> = {}): PendingChange => ({
  approvalId: '00000000-0000-0000-0000-0000000a5001', operation: 'BRANCH_UPDATE', proposedName: 'Windhoek CBD',
  requestedById: 'm', requestedByName: 'Maria Nghipondoka', requestedAt: '2026-11-21T09:00:00Z',
  canDecide: true, blockedReason: null, ...over,
});

beforeEach(() => resetBranchFixtures());

describe('org.branches manifest', () => {
  it('is gated by the Role Management keys and every action names its cell and command', () => {
    expect(orgBranchesWidget.id).toBe('org.branches');
    expect(orgBranchesWidget.requires).toEqual(['role.view', 'role.manage']);
    const actions = orgBranchesWidget.actions ?? [];
    expect(actions.map((a) => [a.id, a.cell, a.command])).toEqual([
      ['branch.create', 'role.manage', 'org.branches.create'],
      ['branch.edit', 'role.manage', 'org.branches.update'],
      ['branch.delete', 'role.manage', 'org.branches.delete'],
      ['branch.approve', 'role.manage', 'org.branches.approve'],
      ['branch.reject', 'role.manage', 'org.branches.reject'],
    ]);
    for (const a of actions) expect(orgBranchesWidget.commandOperations?.[a.command ?? '']).toBeDefined();
    expect(orgBranchesWidget.commandOperations).toEqual({
      'org.branches.create': 'create', 'org.branches.update': 'modify', 'org.branches.delete': 'modify',
      'org.branches.approve': 'approve', 'org.branches.reject': 'approve',
    });
  });

  it('is full width on every tier', () => {
    expect(Object.keys(orgBranchesWidget.size).sort()).toEqual(['lg', 'md', 'sm', 'xs']);
  });
});

describe('org.branches data mapping', () => {
  it('maps every manifest source and command, with a fixture for each', () => {
    for (const s of orgBranchesWidget.sources ?? []) {
      expect(sources[s]?.['*']?.method).toBe('GET');
      expect(fixtures[s]?.default).toBeDefined();
    }
    for (const c of orgBranchesWidget.commands ?? []) {
      expect(['POST', 'PATCH', 'DELETE']).toContain(commands[c]?.['*']?.method);
      expect(fixtures[c]?.default).toBeDefined();
    }
    for (const m of [sources, commands]) {
      for (const def of Object.values(m)) expect(def['*']?.path).not.toContain('?');
    }
    expect(fixtures['org.branches.list']?.empty).toBeDefined();
    expect(fixtures['org.branches.list']?.error).toBeNull();
  });

  it('only allow-listed query params leave the browser', () => {
    const req = resolveSource('org.branches.list', 'ANY')!;
    expect(buildPath(req, { tenantId: 't1', page: '0', size: '20', status: 'CURRENT', sort: 'name,asc', q: 'wind', evil: 'x' }))
      .toBe('/api/tenants/t1/branches?page=0&size=20&sort=name%2Casc&q=wind&status=CURRENT');
  });
});

describe('org.branches list state', () => {
  it('sanitises unknown URL values to the defaults', () => {
    const q = readListQuery((k) => new URLSearchParams('status=BOGUS&sort=password,asc&size=7&page=-3&q=  walvis ').get(k));
    expect(q).toEqual({ page: 0, size: 20, q: 'walvis', status: 'CURRENT', sort: 'name,asc' });
  });

  it('never sends a search shorter than 2 characters', () => {
    const base = { page: 1, size: 50, status: 'AWAITING' as const, sort: 'createdAt,desc' as const };
    expect(listParams('t1', { ...base, q: 'w' })).toEqual({ tenantId: 't1', page: '1', size: '50', status: 'AWAITING', sort: 'createdAt,desc' });
    expect(listParams('t1', { ...base, q: ' wa ' }).q).toBe('wa');
  });
});

describe('org.branches validation', () => {
  it('puts the right message on the name', () => {
    expect(nameError('')).toBe('Enter a branch name of at least 2 characters');
    expect(nameError(' W ')).toBe('Enter a branch name of at least 2 characters');
    expect(nameError('x'.repeat(81))).toBe('Keep the name under 80 characters');
    expect(nameError('<script>')).toMatch(/^Use letters, digits/);
    expect(nameError('Lüderitz Waterfront (Shop 4)')).toBeNull();
  });

  it('normalises spacing and compares names without case', () => {
    expect(branchFormSchema.parse({ name: '  Walvis   Bay ' }).name).toBe('Walvis Bay');
    expect(isSameName('walvis  bay', 'Walvis Bay')).toBe(true);
    expect(isSameName('Walvis Bay Port', 'Walvis Bay')).toBe(false);
  });
});

describe('org.branches request bodies', () => {
  it('builds exactly what core receives', () => {
    expect(createBody({ name: 'Oshakati' })).toEqual({ name: 'Oshakati' });
    expect(updateBody(row(), { name: 'Windhoek  CBD' })).toEqual({ name: 'Windhoek CBD', expectedVersion: 3 });
    expect(deleteParams('t1', row())).toEqual({ tenantId: 't1', branchId: 'b1', expectedVersion: '3' });
    expect(decisionBody('  ')).toEqual({ reason: null });
    expect(decisionBody(' Duplicate ')).toEqual({ reason: 'Duplicate' });
  });
});

describe('org.branches display', () => {
  it('says what state a branch is in, pending changes first', () => {
    expect(statusView(row())).toEqual({ label: 'Active', tone: 'ok' });
    expect(statusView(row({ status: 'PENDING' }))).toEqual({ label: 'Awaiting approval', tone: 'warn' });
    expect(statusView(row({ pending: pending() })).label).toBe('Rename awaiting approval');
    expect(statusView(row({ pending: pending({ operation: 'BRANCH_DELETE' }) })).label).toBe('Deletion awaiting approval');
    expect(statusView(row({ status: 'REJECTED' })).tone).toBe('bad');
    expect(statusView(row({ status: 'ARCHIVED' })).label).toBe('Deleted');
  });

  it('fills the Approved by column', () => {
    expect(approvedByText(row())).toBe('Peter Amutenya');
    expect(approvedByText(row({ status: 'PENDING', checkerName: null }))).toBe('Awaiting checker');
    expect(approvedByText(row({ status: 'REJECTED' }))).toBe('Rejected by Peter Amutenya');
  });

  it('explains why a branch cannot be changed', () => {
    expect(changeBlocker(row(), true)).toBeNull();
    expect(changeBlocker(row(), false)).toMatch(/role\.manage/);
    expect(changeBlocker(row({ pending: pending() }), true)).toMatch(/already awaiting approval/);
    expect(changeBlocker(row({ status: 'PENDING' }), true)).toMatch(/first approval/);
    expect(changeBlocker(row({ status: 'ARCHIVED' }), true)).toMatch(/Only active/);
  });

  it('uses the mandated four-eyes wording, never hiding a refusal', () => {
    expect(blockedText('OWN_REQUEST')).toBe('You submitted this — another person must decide.');
    expect(blockedText('NOT_CHECKER')).toBe('You aren’t enabled as a checker.');
    expect(blockedText(null)).toBeNull();
    expect(problemText({ code: 'MAKER_CANNOT_APPROVE', message: 'x' })).toBe(MAKER_BLOCKED);
    expect(problemText({ code: 'CHECKER_NOT_ENABLED', message: 'x' })).toBe(NOT_CHECKER);
    expect(problemText({ code: 'STALE_WRITE', message: '' })).toMatch(/Reload/);
    expect(problemText({ code: 'SOMETHING_NEW', message: 'Server says no' })).toBe('Server says no');
  });

  it('confirms filings and decisions in plain words', () => {
    expect(filedNotice('BRANCH_CREATE', 'Oshakati', '00000000-0000-0000-0000-0000000a5001'))
      .toBe('“Oshakati” sent for approval (ref 000A5001) — a different person must approve it before it can be used.');
    expect(filedNotice('BRANCH_DELETE', 'Keetmanshoop', null)).toMatch(/^Deletion of “Keetmanshoop” sent for approval — /);
    expect(decidedNotice(row(), pending(), true)).toBe('Rename approved — the branch is now “Windhoek CBD”.');
    expect(decidedNotice(row(), pending(), false)).toBe('Rejected — Maria Nghipondoka will see your reason.');
  });
});

describe('org.branches fixture server (core contract)', () => {
  it('searches, filters, sorts and pages on the server with totals', async () => {
    const p = port();
    const first = await list(p, { size: '10' });
    expect(first.items).toHaveLength(10);
    expect(first.totalElements).toBe(24); // PENDING + ACTIVE only: rejected / deleted are not "current"
    expect(first.hasNext).toBe(true);
    const names = first.items.map((b) => b.name);
    expect([...names].sort((a, b) => a.localeCompare(b))).toEqual(names);
    expect((await list(p, { q: 'walvis' })).items.map((b) => b.name)).toEqual(['Walvis Bay']);
    expect((await list(p, { q: 'town centre' })).items.map((b) => b.name)).toEqual(['Swakopmund']); // proposed name
    expect((await list(p, { status: 'AWAITING' })).items.map((b) => b.name).sort()).toEqual(['Keetmanshoop', 'Oshakati', 'Swakopmund']);
    expect((await list(p, { status: 'REJECTED' })).items.map((b) => b.name)).toEqual(['Rundu Service Point']);
    expect((await list(p, { status: 'ARCHIVED' })).items.map((b) => b.name)).toEqual(['Lüderitz']);
  });

  it('decides per caller who may approve', async () => {
    const p = port();
    expect((await byName(p, 'Oshakati')).pending).toMatchObject({ canDecide: true, blockedReason: null });
    expect((await byName(p, 'Swakopmund')).pending).toMatchObject({ canDecide: false, blockedReason: 'OWN_REQUEST' });
    expect((await byName(port(VIEWER), 'Oshakati')).pending).toMatchObject({ canDecide: false, blockedReason: 'NO_PERMISSION' });
  });

  it('files a new branch as PENDING — the maker cannot approve it, a checker can', async () => {
    const p = port();
    const c = await p.command<BranchChange>('org.branches.create', createBody({ name: 'Ongwediva' }), { tenantId: 't' });
    expect(c).toMatchObject({ operationType: 'BRANCH_CREATE', status: 'PENDING_CHECKER' });
    const filed = await byName(p, 'Ongwediva');
    expect(filed).toMatchObject({ status: 'PENDING', makerName: FIXTURE_ME.name, checkerName: null });
    expect(filed.pending).toMatchObject({ blockedReason: 'OWN_REQUEST', canDecide: false });
    expect(await code(() => p.command('org.branches.approve', { reason: null }, { id: c.pendingApprovalId })))
      .toBe('403 MAKER_CANNOT_APPROVE');

    const other = await byName(p, 'Oshakati');
    await p.command('org.branches.approve', { reason: null }, { id: other.pending!.approvalId });
    expect(await byName(p, 'Oshakati', 'ACTIVE')).toMatchObject({ status: 'ACTIVE', checkerName: FIXTURE_ME.name, pending: null });
  });

  it('applies a rename and a deletion only after approval', async () => {
    const p = port();
    const walvis = await byName(p, 'Walvis Bay');
    await p.command('org.branches.update', updateBody(walvis, { name: 'Walvis Bay Harbour' }), { tenantId: 't', branchId: walvis.id });
    const filed = await p.request<BranchRow>('org.branches.get', { tenantId: 't', branchId: walvis.id });
    expect(filed.name).toBe('Walvis Bay');
    expect(filed.pending).toMatchObject({ operation: 'BRANCH_UPDATE', proposedName: 'Walvis Bay Harbour' });

    const keet = await byName(p, 'Keetmanshoop');
    await p.command('org.branches.approve', { reason: null }, { id: keet.pending!.approvalId });
    expect((await list(p, { status: 'ARCHIVED' })).items.map((b) => b.name)).toContain('Keetmanshoop');
  });

  it('a rejection needs a reason, and a rejected creation stays visible as REJECTED', async () => {
    const p = port();
    const osh = await byName(p, 'Oshakati');
    expect(await code(() => p.command('org.branches.reject', { reason: null }, { id: osh.pending!.approvalId })))
      .toBe('422 REASON_REQUIRED');
    await p.command('org.branches.reject', { reason: 'Opens next year' }, { id: osh.pending!.approvalId });
    expect(await byName(p, 'Oshakati', 'REJECTED')).toMatchObject({ decisionReason: 'Opens next year', checkerName: FIXTURE_ME.name });
  });

  it('refuses stale, duplicate, double-pending and in-use changes', async () => {
    const p = port();
    const walvis = await byName(p, 'Walvis Bay');
    const params = { tenantId: 't', branchId: walvis.id };
    expect(await code(() => p.command('org.branches.update', { name: 'Walvis', expectedVersion: walvis.version - 1 }, params)))
      .toBe('409 STALE_WRITE');
    expect(await code(() => p.command('org.branches.update', updateBody(walvis, { name: 'katima mulilo' }), params)))
      .toBe('409 BRANCH_NAME_TAKEN');
    expect(await code(() => p.command('org.branches.create', { name: 'Swakopmund Town Centre' }, { tenantId: 't' })))
      .toBe('409 BRANCH_NAME_TAKEN'); // a name already proposed counts
    const swk = await byName(p, 'Swakopmund');
    expect(await code(() => p.command('org.branches.delete', undefined, deleteParams('t', swk))))
      .toBe('409 BRANCH_CHANGE_PENDING');
    const hq = await p.request<BranchRow>('org.branches.get', { tenantId: 't', branchId: IN_USE_BRANCH_ID });
    expect(await code(() => p.command('org.branches.delete', undefined, deleteParams('t', hq))))
      .toBe('409 BRANCH_IN_USE');
  });

  it('a viewer can read but not write', async () => {
    const p = port(VIEWER);
    expect((await list(p)).items.length).toBeGreaterThan(0);
    expect(await code(() => p.command('org.branches.create', { name: 'Gochas' }, { tenantId: 't' })))
      .toBe('403 PERMISSION_DENIED');
  });
});
