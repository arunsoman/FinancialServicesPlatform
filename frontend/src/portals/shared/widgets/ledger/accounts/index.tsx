/** ledger.accounts — chart of accounts per book (+ default provisioning). */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { LedgerAccounts } from './widget';

const ledgerAccountsWidget = defineWidget({
  id: 'ledger.accounts',
  version: '1.0.0',
  label: 'Chart of accounts',
  description: 'GL accounts of a book; provision the default chart on an empty book.',
  category: 'Ledger',
  contexts: ['ANY'],
  requires: ['gl.account.view'],
  sources: ['ledger.books', 'ledger.accounts.list'],
  commands: ['ledger.accounts.provision'],
  actions: [{ id: 'coa.provision', label: 'Provision default chart', cell: 'gl.account.create', command: 'ledger.accounts.provision', kind: 'primary' }],
  commandOperations: { 'ledger.accounts.provision': 'create' },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  render: LedgerAccounts,
});

export default ledgerAccountsWidget;
