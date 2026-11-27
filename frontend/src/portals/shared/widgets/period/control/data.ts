/**
 * period.control data contributions — accounting periods per book (core LedgerController).
 * READ-ONLY until core exposes period open/close — the manifest carries no commands, so the
 * fixtures play reads only.
 */
import type { DataSources } from '../../core/sources';
import type { FixtureSet, FixtureRequest } from '../../core/ports';
import { WB_BOOK } from '../../kit/fixture-ledger';
import { forbidUnless } from '../../kit/forbid';

export const sources: DataSources = {
  'period.control.list': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/books/{bookId}/periods' } },
};

export const fixtures: FixtureSet = {
  'period.control.list': {
    default: (req: FixtureRequest) => {
      forbidUnless(req.permissions, 'gl.account.view');
      return [
        { id: '00000000-0000-0000-0000-0000000000f1', fiscalYear: 2026, periodNo: 11, startDate: '2026-11-01', endDate: '2026-11-30', status: 'OPEN' },
        { id: '00000000-0000-0000-0000-0000000000f2', fiscalYear: 2026, periodNo: 10, startDate: '2026-10-01', endDate: '2026-10-31', status: 'SOFT_CLOSED' },
        { id: '00000000-0000-0000-0000-0000000000f3', fiscalYear: 2026, periodNo: 9, startDate: '2026-09-01', endDate: '2026-09-30', status: 'HARD_CLOSED' },
      ];
    },
    empty: [],
    error: null,
  },
};

/** Kept exported so tests and sibling widgets reference the same book id (no string drift). */
export const PERIOD_BOOK_ID = WB_BOOK;
