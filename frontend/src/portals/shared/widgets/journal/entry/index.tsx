/** journal.entry — draft, submit, approve (four-eyes) and settle uncertain journal entries. */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { JournalEntries } from './widget';

const journalEntryWidget = defineWidget({
  id: 'journal.entry',
  version: '1.0.0',
  label: 'Journal entries',
  description: 'Create balanced entries, submit them for approval, approve as a different person, and check uncertain postings.',
  category: 'Ledger',
  contexts: ['ANY'],
  // gl.account.view is a supporting read: the books picker (ledger.books, kit/books.ts) is the
  // same call core answers with a gl.account.view check — and every seeded role holding je.*
  // also holds gl.account.view. Without it the workbench's cell picker can never satisfy the
  // shared fixture and the preview is a permanent "forbidden" wall.
  requires: ['je.view', 'je.create', 'je.approve', 'gl.account.view'],
  sources: ['journal.entry.list', 'journal.entry.get', 'journal.entry.accounts', 'ledger.books'],
  commands: ['journal.entry.create', 'journal.entry.submit', 'journal.entry.approve', 'journal.entry.check'],
  actions: [
    { id: 'je.create', label: 'New entry', cell: 'je.create', command: 'journal.entry.create', kind: 'primary' },
    { id: 'je.submit', label: 'Submit for approval', cell: 'je.create', command: 'journal.entry.submit', kind: 'primary' },
    { id: 'je.approve', label: 'Approve and post', cell: 'je.approve', command: 'journal.entry.approve', kind: 'primary' },
    { id: 'je.check', label: 'Check status', cell: 'je.approve', command: 'journal.entry.check' },
  ],
  commandOperations: {
    'journal.entry.create': 'create', 'journal.entry.submit': 'modify', 'journal.entry.approve': 'approve', 'journal.entry.check': 'approve',
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  render: JournalEntries,
});

export default journalEntryWidget;
