/** ledger.accounts data contributions — chart of accounts per book. */
import type { DataSources } from '../../core/sources';
import type { FixtureSet, FixtureRequest } from '../../core/ports';
import type { GlAccountRow } from '../../kit/books';
import { LEDGER_FIXTURES, WB_GL_ACCOUNTS } from '../../kit/fixture-ledger';
import { forbidUnless } from '../../kit/forbid';

export const sources: DataSources = {
  'ledger.accounts.list': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/books/{bookId}/gl-accounts' } },
};

export const commands: DataSources = {
  'ledger.accounts.provision': { '*': { method: 'POST', path: '/api/tenants/{tenantId}/books/{bookId}/provision-default-coa' } },
};

export const fixtures: FixtureSet = {
  ...LEDGER_FIXTURES,
  'ledger.accounts.provision': {
    // Four-eyes WRITE with an Idempotency-Key — fixture lane replays only in the default scenario.
    default: (req: FixtureRequest): GlAccountRow[] => {
      forbidUnless(req.permissions, 'gl.account.create');
      void req.body; // core ignores the body; the CoA is deterministic per book+currency
      return WB_GL_ACCOUNTS;
    },
    error: null,
  },
};
