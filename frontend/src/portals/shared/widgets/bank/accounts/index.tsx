/** bank.accounts — settlement bank accounts (F-10): maker files, checker approves. */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { BankAccountsWidget } from './widget';

const bankAccountsWidget = defineWidget({
  id: 'bank.accounts',
  version: '1.0.0',
  label: 'Settlement bank accounts',
  description: 'The organization’s payout destination: maker files it, a checker approves it before it goes active.',
  category: 'Ledger',
  contexts: ['ANY'],
  requires: ['bank.account.view'],
  sources: ['bank.accounts.list'],
  commands: ['bank.accounts.create'],
  actions: [{ id: 'bank.account.file', label: 'Request settlement account', cell: 'bank.account.create', command: 'bank.accounts.create', kind: 'primary' }],
  commandOperations: { 'bank.accounts.create': 'create' },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  render: BankAccountsWidget,
});

export default bankAccountsWidget;
