/** approvals.queue data contributions (sources.ts WIDGET_DATA): names start with the widget id. */
import type { DataSources } from '../../core/sources';
import type { FixtureSet, FixtureRequest } from '../../core/ports';
import type { QueueRow } from './model';

export const sources: DataSources = {
  'approvals.queue.list': { '*': { method: 'GET', path: '/api/approvals?tenantId={tenantId}', query: ['status', 'size'] } },
};

export const commands: DataSources = {
  'approvals.queue.approve': { '*': { method: 'POST', path: '/api/approvals/{id}/approve' } },
  'approvals.queue.reject': { '*': { method: 'POST', path: '/api/approvals/{id}/reject' } },
};

/** Fixture-lane rows — same shape core ApprovalsController returns (model.ts QueueRow).
 *  canDecide/blockedReason mirror the server's four-eyes verdicts, so the widget renders
 *  its real states: decidable · own-request blocked · already decided. */
const T = '2026-11-20T09:00:00Z';
const MAKER = '00000000-0000-0000-0000-0000000002d2';
const CHECKER = '00000000-0000-0000-0000-0000000002d1';

const ROWS: QueueRow[] = [
  {
    id: '00000000-0000-0000-0000-0000000009a1', tenantId: '00000000-0000-0000-0000-0000000000a1',
    operationType: 'JE_POSTING', targetResourceId: '00000000-0000-0000-0000-0000000000g1',
    status: 'PENDING_CHECKER', makerId: MAKER, makerName: 'Johannes Kapuka', checkerId: null, checkerName: null,
    reason: null, requestedAt: T, decidedAt: null, canDecide: true, blockedReason: null, checkerPermission: 'je.approve',
    payload: '{"entryId":"g1","totalDebits":"250000","totalCredits":"250000","currency":"NAD"}',
  },
  {
    id: '00000000-0000-0000-0000-0000000009a2', tenantId: '00000000-0000-0000-0000-0000000000a1',
    operationType: 'USER_CREATE', targetResourceId: '00000000-0000-0000-0000-0000000009b1',
    status: 'PENDING_CHECKER', makerId: MAKER, makerName: 'Johannes Kapuka', checkerId: null, checkerName: null,
    reason: null, requestedAt: T, decidedAt: null, canDecide: false, blockedReason: 'OWN_REQUEST', checkerPermission: 'user.manage',
    payload: '{"username":"new.teller","fullName":"New Teller"}',
  },
  {
    id: '00000000-0000-0000-0000-0000000009a3', tenantId: '00000000-0000-0000-0000-0000000000a1',
    operationType: 'BANK_ACCOUNT_CREATE', targetResourceId: '00000000-0000-0000-0000-0000000009c1',
    status: 'APPROVED', makerId: MAKER, makerName: 'Johannes Kapuka', checkerId: CHECKER, checkerName: 'Anna Shikongo',
    reason: null, requestedAt: T, decidedAt: T, canDecide: false, blockedReason: 'DECIDED', checkerPermission: 'bank.account.approve',
    payload: null,
  },
];

export const fixtures: FixtureSet = {
  'approvals.queue.list': {
    default: (req: FixtureRequest): QueueRow[] => {
      const status = (req.params.status ?? '').toUpperCase();
      return status && status !== 'ALL' ? ROWS.filter((r) => r.status === status) : ROWS;
    },
    empty: [],
    error: null,
  },
};
