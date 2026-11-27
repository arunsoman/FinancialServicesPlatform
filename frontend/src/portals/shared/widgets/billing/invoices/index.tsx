/** billing.invoices — sales + purchase invoices, 30-day credit by default, built-in payment options. */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { Invoices } from './widget';

const invoicesWidget = defineWidget({
  id: 'billing.invoices',
  version: '1.0.0',
  label: 'Invoices',
  description: 'Issue invoices on 30-day credit (by default), track what is owed and overdue, and pay by e-money wallet, bank transfer or card.',
  category: 'Billing',
  contexts: ['ANY'],
  requires: ['invoice.view'],
  sources: ['billing.invoices.list', 'billing.invoices.summary', 'billing.invoices.get', 'billing.invoices.options', 'billing.invoices.counterparties'],
  commands: ['billing.invoices.create', 'billing.invoices.issue', 'billing.invoices.pay', 'billing.invoices.confirm', 'billing.invoices.void'],
  actions: [
    { id: 'invoice.create', label: 'New invoice', cell: 'invoice.create', command: 'billing.invoices.create', kind: 'primary' },
    { id: 'invoice.issue', label: 'Issue invoice', cell: 'invoice.create', command: 'billing.invoices.issue', kind: 'primary' },
    { id: 'invoice.pay', label: 'Pay', cell: 'invoice.pay', command: 'billing.invoices.pay', kind: 'primary' },
    { id: 'invoice.collect', label: 'Confirm money received', cell: 'invoice.collect', command: 'billing.invoices.confirm', kind: 'primary' },
    { id: 'invoice.void', label: 'Void', cell: 'invoice.void', command: 'billing.invoices.void', kind: 'danger' },
  ],
  commandOperations: {
    'billing.invoices.create': 'create',
    'billing.invoices.issue': 'modify',
    'billing.invoices.pay': 'create',
    'billing.invoices.confirm': 'modify',
    'billing.invoices.void': 'approve',
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  render: Invoices,
});

export default invoicesWidget;
