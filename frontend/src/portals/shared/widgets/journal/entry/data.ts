/** journal.entry data contributions (core JournalController). Money POSTs are never auto-retried. */
import type { DataSources } from '../../core/sources';
import type { FixtureSet, FixtureRequest } from '../../core/ports';
import type { EntryView, LineView } from './model';
import type { GlAccountRow } from '../../kit/books';
import { LEDGER_FIXTURES, WB_BOOK, WB_TENANT, WB_GL_ACCOUNTS } from '../../kit/fixture-ledger';
import { forbidUnless } from '../../kit/forbid';

export const sources: DataSources = {
  'journal.entry.list': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/journal-entries', query: ['status', 'size'] } },
  'journal.entry.get': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/journal-entries/{entryId}' } },
  'journal.entry.accounts': { '*': { method: 'GET', path: '/api/tenants/{tenantId}/books/{bookId}/gl-accounts' } },
};

export const commands: DataSources = {
  'journal.entry.create': { '*': { method: 'POST', path: '/api/tenants/{tenantId}/journal-entries' } },
  'journal.entry.submit': { '*': { method: 'POST', path: '/api/tenants/{tenantId}/journal-entries/{entryId}/submit-for-approval' } },
  'journal.entry.approve': { '*': { method: 'POST', path: '/api/tenants/{tenantId}/journal-entries/{entryId}/approve' } },
  'journal.entry.check': { '*': { method: 'POST', path: '/api/tenants/{tenantId}/journal-entries/{entryId}/check-status' } },
};

/**
 * Seeded entries — money as MINOR-UNIT strings (ui-std §5.3), balanced per book+currency,
 * one COMMIT_UNKNOWN so the operator queue surfaces (ui-std §5.1). Mutable so the fixture
 * lane plays the posting state machine: DRAFT → PENDING_CHECKER → FINALIZED.
 */
let entries: EntryView[] = [
  {
    entryId: '00000000-0000-0000-0000-0000000000g1',
    tenantId: WB_TENANT, bookId: WB_BOOK, currency: 'NAD',
    entryType: 'MANUAL', postingDate: '2026-11-10', valueDate: '2026-11-10',
    description: 'Coffee sale — counter', reference: null,
    totalDebits: '250000', totalCredits: '250000', status: 'FINALIZED',
    planHash: 'urn:hash::sha256:1f2e3d4c5b6a',
    tbTransferIds: ['00000000-0000-0000-0000-0000000000h1', '00000000-0000-0000-0000-0000000000h2'],
    makerId: '00000000-0000-0000-0000-0000000000f4', checkerId: '00000000-0000-0000-0000-0000000000f5',
    finalizedAt: '2026-11-10T12:00:00Z', version: 4,
    lines: [
      { lineNo: 1, glAccountId: '00000000-0000-0000-0000-0000000000e1', direction: 'DEBIT', amount: '250000', memo: null },
      { lineNo: 2, glAccountId: '00000000-0000-0000-0000-0000000000e4', direction: 'CREDIT', amount: '250000', memo: null },
    ],
  },
  {
    entryId: '00000000-0000-0000-0000-0000000000g2',
    tenantId: WB_TENANT, bookId: WB_BOOK, currency: 'NAD',
    entryType: 'MANUAL', postingDate: '2026-11-15', valueDate: '2026-11-15',
    description: 'Payroll accrual — November', reference: 'PAY-2026-11',
    totalDebits: '180000', totalCredits: '180000', status: 'COMMIT_UNKNOWN',
    planHash: 'urn:hash::sha256:9a8b7c6d5e4f',
    tbTransferIds: ['00000000-0000-0000-0000-0000000000h3', '00000000-0000-0000-0000-0000000000h4'],
    makerId: '00000000-0000-0000-0000-0000000000f4', checkerId: '00000000-0000-0000-0000-0000000000f5',
    finalizedAt: null, version: 5,
    lines: [
      { lineNo: 1, glAccountId: '00000000-0000-0000-0000-0000000000e5', direction: 'DEBIT', amount: '180000', memo: null },
      { lineNo: 2, glAccountId: '00000000-0000-0000-0000-0000000000e1', direction: 'CREDIT', amount: '180000', memo: null },
    ],
  },
];

function findOrThrow(entryId: string): EntryView {
  const e = entries.find((x) => x.entryId === entryId);
  if (!e) throw new Error(`fixture drift: journal entry ${entryId} not in defaults`);
  return e;
}

export const fixtures: FixtureSet = {
  ...LEDGER_FIXTURES,
  'journal.entry.accounts': {
    // postable accounts under one book (core GET /books/{bookId}/gl-accounts); same guard as core.
    default: (req: FixtureRequest): GlAccountRow[] => {
      forbidUnless(req.permissions, 'gl.account.view');
      const bookId = req.params.bookId || WB_BOOK;
      return WB_GL_ACCOUNTS.filter((a) => a.bookId === bookId);
    },
    empty: [],
    error: null,
  },
  'journal.entry.list': {
    default: (req: FixtureRequest): EntryView[] => {
      forbidUnless(req.permissions, 'je.view');
      const status = req.params.status;
      return status && status !== 'ALL' ? entries.filter((e) => e.status === status) : entries;
    },
    empty: [],
    error: null,
  },
  'journal.entry.get': {
    default: (req: FixtureRequest): EntryView => {
      forbidUnless(req.permissions, 'je.view');
      return findOrThrow(req.params.entryId ?? '');
    },
    error: null,
  },
  'journal.entry.create': {
    default: (req: FixtureRequest): EntryView => {
      forbidUnless(req.permissions, 'je.create');
      const b = req.body as {
        bookId: string; postingDate: string; valueDate: string; description: string;
        reference: string | null; lines: Array<{ glAccountId: string; direction: string; amount: string; memo: string | null }>;
      };
      const lines: LineView[] = b.lines.map((l, i) => ({ lineNo: i + 1, glAccountId: l.glAccountId, direction: l.direction as 'DEBIT' | 'CREDIT', amount: l.amount, memo: l.memo }));
      const debits = lines.filter((l) => l.direction === 'DEBIT').reduce((s, l) => s + Number(l.amount), 0);
      const n = entries.length + 1;
      const entry: EntryView = {
        entryId: `00000000-0000-0000-0000-0000000000g${n}`,
        tenantId: WB_TENANT, bookId: b.bookId, currency: 'NAD',
        entryType: 'MANUAL', postingDate: b.postingDate, valueDate: b.valueDate,
        description: b.description, reference: b.reference,
        totalDebits: String(debits), totalCredits: String(debits), status: 'DRAFT',
        planHash: null, tbTransferIds: null,
        makerId: '00000000-0000-0000-0000-0000000000f4', checkerId: null,
        finalizedAt: null, version: 1, lines,
      };
      entries = [...entries, entry];
      return entry;
    },
    error: null,
  },
  'journal.entry.submit': {
    default: (req: FixtureRequest): EntryView => {
      forbidUnless(req.permissions, 'je.create');
      const e = findOrThrow(req.params.entryId ?? '');
      if (e.status !== 'DRAFT') throw new Error(`fixture drift: submit from ${e.status} is a JE_STATE violation`);
      entries = entries.map((x) => (x.entryId === e.entryId ? { ...x, status: 'PENDING_CHECKER', version: x.version + 1 } : x));
      return { ...e, status: 'PENDING_CHECKER', version: e.version + 1 };
    },
    error: null,
  },
  'journal.entry.approve': {
    default: (req: FixtureRequest): EntryView => {
      forbidUnless(req.permissions, 'je.approve');
      const e = findOrThrow(req.params.entryId ?? '');
      if (e.status !== 'PENDING_CHECKER') throw new Error(`fixture drift: approve from ${e.status} is a JE_STATE violation`);
      entries = entries.map((x) => (x.entryId === e.entryId ? { ...x, status: 'FINALIZED', checkerId: '00000000-0000-0000-0000-0000000000f5', finalizedAt: '2026-11-16T10:00:00Z', version: x.version + 1 } : x));
      return { ...e, status: 'FINALIZED', checkerId: '00000000-0000-0000-0000-0000000000f5', finalizedAt: '2026-11-16T10:00:00Z', version: e.version + 1 };
    },
    error: null,
  },
  'journal.entry.check': {
    // ui-std §5.1: check-status resolves COMMIT_UNKNOWN with the ORIGINAL transfer ids — never fresh ones.
    default: (req: FixtureRequest): EntryView => {
      forbidUnless(req.permissions, 'je.approve');
      const e = findOrThrow(req.params.entryId ?? '');
      if (e.status !== 'COMMIT_UNKNOWN') return { ...e };
      entries = entries.map((x) => (x.entryId === e.entryId ? { ...x, status: 'FINALIZED', finalizedAt: '2026-11-16T10:05:00Z', version: x.version + 1 } : x));
      return { ...e, status: 'FINALIZED', finalizedAt: '2026-11-16T10:05:00Z', version: e.version + 1 };
    },
    error: null,
  },
};

export const JOURNAL_ORG_ID = WB_TENANT;
