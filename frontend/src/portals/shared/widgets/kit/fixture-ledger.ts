/**
 * Shared ledger fixtures — books + chart of accounts. The period, ledger and journal
 * widgets all read core's `ledger.books` / `ledger.accounts.list` sources, so the
 * workbench's fixture lane answers them with the SAME WB-scoped rows (fixtures.ts keeps
 * one identity per row). Money never appears here as a number.
 */
import type { FixtureSet, FixtureRequest } from '../core/ports';
import type { BookRow, GlAccountRow } from './books';
import { forbidUnless } from './forbid';

export const WB_TENANT = '00000000-0000-0000-0000-0000000000a1';
export const WB_BOOK = '00000000-0000-0000-0000-0000000000d1';

function account(
  id: string, code: number, name: string, accountType: string,
  normalBalance: string, balanceBound: string,
): GlAccountRow {
  return {
    id,
    bookId: WB_BOOK,
    code,
    name,
    accountType,
    normalBalance,
    balanceBound,
    currency: 'NAD',
    status: 'ACTIVE',
  };
}

/** A Namibian merchant chart as core's default CoA provisioner would create it. */
export const WB_GL_ACCOUNTS: GlAccountRow[] = [
  account('00000000-0000-0000-0000-0000000000e1', 1010, 'Cash on hand', 'ASSET', 'DEBIT', 'DEBIT'),
  account('00000000-0000-0000-0000-0000000000e2', 1200, 'Accounts receivable', 'ASSET', 'DEBIT', 'DEBIT'),
  account('00000000-0000-0000-0000-0000000000e3', 2010, 'VAT payable', 'LIABILITY', 'CREDIT', 'CREDIT'),
  account('00000000-0000-0000-0000-0000000000e4', 4010, 'Coffee sales', 'REVENUE', 'CREDIT', 'BOTH'),
  account('00000000-0000-0000-0000-0000000000e5', 5010, 'Payroll expenses', 'EXPENSE', 'DEBIT', 'DEBIT'),
];

const books: BookRow[] = [
  { id: WB_BOOK, tenantId: WB_TENANT, name: 'Main ledger', currency: 'NAD', ledgerId: 516, status: 'ACTIVE' },
  { id: '00000000-0000-0000-0000-0000000000d2', tenantId: WB_TENANT, name: 'Settlement ledger', currency: 'ZAR', ledgerId: 710, status: 'ACTIVE' },
];

/** Books + active chart; both guarded exactly like core (gl.account.view on the tenant subtree). */
export const LEDGER_FIXTURES: FixtureSet = {
  'ledger.books': {
    default: (req: FixtureRequest) => {
      forbidUnless(req.permissions, 'gl.account.view');
      return books;
    },
    empty: [],
    error: null,
  },
  'ledger.accounts.list': {
    default: (req: FixtureRequest) => {
      forbidUnless(req.permissions, 'gl.account.view');
      return WB_GL_ACCOUNTS;
    },
    empty: [],
    error: null,
  },
};
