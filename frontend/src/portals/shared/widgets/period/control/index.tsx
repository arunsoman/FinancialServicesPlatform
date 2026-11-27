/** period.control — accounting periods per book (state view; transitions arrive with the period-close slice). */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { PeriodControl } from './widget';

const periodControlWidget = defineWidget({
  id: 'period.control',
  version: '1.0.0',
  label: 'Accounting periods',
  description: 'Accounting periods of a book and whether each is open or closed.',
  category: 'Ledger',
  contexts: ['ANY'],
  requires: ['gl.account.view', 'period.open', 'period.close'],
  sources: ['ledger.books', 'period.control.list'],
  commands: [],
  actions: [],
  commandOperations: {},
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  render: PeriodControl,
});

export default periodControlWidget;
