/**
 * org.branches — data contributions. Names are scoped to the widget id (org.branches.*);
 * core/sources.ts merges the named `sources` / `commands` / `fixtures` exports.
 *
 * BFF /api/... → gateway /v1/... → core. The list path exists today (OrgUnitController,
 * org.branch.list); its response grows the maker/checker/pending fields and the status filter
 * grows CURRENT / AWAITING in stage 2. get / create / update / delete are NEW (stage 2).
 * Decisions REUSE core approvals (approval.resolve.approve / .reject, four-eyes on person_id).
 */
import type { DataSources } from '../../core/sources';
import { BRANCH_FIXTURES } from './fixture-server';

export const sources: DataSources = {
  'org.branches.list': {
    '*': { method: 'GET', path: '/api/tenants/{tenantId}/branches', query: ['page', 'size', 'sort', 'q', 'status'] },
  },
  'org.branches.get': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/branches/{branchId}' } },
};

export const commands: DataSources = {
  'org.branches.create': { '*': { method: 'POST', path: '/api/tenants/{tenantId}/branches' } },
  'org.branches.update': { '*': { method: 'PATCH', path: '/api/tenants/{tenantId}/branches/{branchId}' } },
  'org.branches.delete': {
    '*': { method: 'DELETE', path: '/api/tenants/{tenantId}/branches/{branchId}', query: ['expectedVersion'] },
  },
  'org.branches.approve': { '*': { method: 'POST', path: '/api/approvals/{id}/approve' } },
  'org.branches.reject': { '*': { method: 'POST', path: '/api/approvals/{id}/reject' } },
};

/** Workbench scenarios — a stateful in-memory core (fixture-server.ts): default · empty · error. */
export const fixtures = BRANCH_FIXTURES;
