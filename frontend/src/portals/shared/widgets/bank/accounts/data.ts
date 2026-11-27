/**
 * bank.accounts (F-10) — data contributions: list source + file command.
 * Names are prefix-scoped to the widget id (bank.accounts.*). Exported as named
 * bindings (sources/commands) — that is what `contributed()` in core/sources.ts merges.
 */
import type { DataSources } from '../../core/sources';
import type { FixtureSet } from '../../core/ports';
import type { BankAccountRow } from './model';

// core BankAccountController — BFF /api/tenants/{tenantId}/bank-accounts → gateway /v1/...
export const sources: DataSources = {
  'bank.accounts.list': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/bank-accounts' } },
};

// maker files the org's settlement account (Idempotency-Key is added by web-core/api)
export const commands: DataSources = {
  'bank.accounts.create': { '*': { method: 'POST', path: '/api/tenants/{tenantId}/bank-accounts' } },
};

/** Fixture-lane rows — masked numbers only (ui-std: never a raw account number on screen);
 *  one ACTIVE payout destination and one PENDING maker filing awaiting its checker. */
const T = '2026-11-20T09:00:00Z';
const WB_TENANT = '00000000-0000-0000-0000-0000000000a1';

const row = (o: BankAccountRow): BankAccountRow => o;

export const fixtures: FixtureSet = {
  'bank.accounts.list': {
    default: {
      items: [
        row({
          id: '00000000-0000-0000-0000-0000000008a1', tenantId: WB_TENANT,
          holderName: 'Namibia Coffee Roasters (Pty) Ltd', bankCode: 'BANK_WINDHOEK',
          branchCode: '481772', maskedAccountNumber: '•••• ••42', accountType: 'CURRENT',
          currency: 'NAD', status: 'ACTIVE', approvalId: '00000000-0000-0000-0000-0000000009a3',
          makerName: 'Johannes Kapuka', version: 3, createdAt: T, updatedAt: T,
        }),
        row({
          id: '00000000-0000-0000-0000-0000000008a2', tenantId: WB_TENANT,
          holderName: 'Namibia Coffee Roasters (Pty) Ltd', bankCode: 'FNB_NAMIBIA',
          branchCode: '250655', maskedAccountNumber: '•••• ••07', accountType: 'SETTLEMENT',
          currency: 'NAD', status: 'PENDING', approvalId: null,
          makerName: 'Johannes Kapuka', version: 1, createdAt: T, updatedAt: T,
        }),
      ],
    },
    empty: { items: [] },
    error: null,
  },
};
