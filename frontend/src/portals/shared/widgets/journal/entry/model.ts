/**
 * journal.entry — wire types, the maker form schema and hooks. ui-std §7: financial mutations
 * are never optimistic and have ZERO automatic retries; an uncertain outcome is surfaced as
 * COMMIT_UNKNOWN and only an explicit "Check status" (original transfer ids) resolves it.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import type { WidgetDataPortLike } from '../../core/manifest';
import type { GlAccountRow } from '../../kit/books';

export interface LineView {
  lineNo: number;
  glAccountId: string;
  direction: 'DEBIT' | 'CREDIT';
  amount: string;
  memo: string | null;
}

export interface EntryView {
  entryId: string;
  tenantId: string;
  bookId: string;
  currency: string;
  entryType: string;
  postingDate: string;
  valueDate: string;
  description: string;
  reference: string | null;
  totalDebits: string;
  totalCredits: string;
  status: string;
  planHash: string | null;
  tbTransferIds: string[] | null;
  makerId: string;
  checkerId: string | null;
  finalizedAt: string | null;
  version: number;
  lines: LineView[];
}

export const STATUS_FILTERS = ['ALL', 'DRAFT', 'PENDING_CHECKER', 'COMMIT_UNKNOWN', 'FINALIZED', 'REJECTED'] as const;
export type JournalFilter = (typeof STATUS_FILTERS)[number];

/** Major-unit text ("2500.00") → minor-unit string ("250000") without floating point. */
export function toMinor(major: string, exponent = 2): string | null {
  const m = /^(\d{1,15})(?:\.(\d{1,3}))?$/.exec(major.trim().replace(/,/g, ''));
  if (!m) return null;
  const frac = (m[2] ?? '').padEnd(exponent, '0');
  if (frac.length > exponent) return null;
  const minor = `${m[1]}${frac}`.replace(/^0+(?=\d)/, '');
  return minor === '0' ? null : minor;
}

const line = z.object({
  glAccountId: z.string().min(1, 'Choose an account'),
  direction: z.enum(['DEBIT', 'CREDIT']),
  amount: z.string().trim().refine((v) => toMinor(v) !== null, 'Enter an amount greater than zero, e.g. 2500.00'),
  memo: z.string().trim().max(200),
});

export const entrySchema = z.object({
  postingDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Choose the posting date'),
  description: z.string().trim().min(2, 'Describe the entry').max(200),
  reference: z.string().trim().max(64),
  lines: z.array(line).min(2, 'A journal entry needs at least two lines'),
}).refine((v) => v.lines.some((l) => l.direction === 'DEBIT') && v.lines.some((l) => l.direction === 'CREDIT'), {
  path: ['lines'], message: 'Include at least one debit and one credit line',
});
export type EntryValues = z.infer<typeof entrySchema>;
export type LineValues = EntryValues['lines'][number];

export const journalKeys = {
  all: (orgId: string) => ['journal', orgId] as const,
  list: (orgId: string, status: JournalFilter) => ['journal', orgId, 'list', status] as const,
  detail: (orgId: string, entryId: string) => ['journal', orgId, 'entry', entryId] as const,
  accounts: (orgId: string, bookId: string) => ['journal', orgId, 'accounts', bookId] as const,
};

export function useEntries(port: WidgetDataPortLike, orgId: string, status: JournalFilter) {
  return useQuery({
    queryKey: journalKeys.list(orgId, status),
    queryFn: () => port.request<EntryView[]>('journal.entry.list', {
      tenantId: orgId, size: '50', ...(status === 'ALL' ? {} : { status }),
    }),
    enabled: orgId !== '',
    placeholderData: (prev) => prev,
  });
}

export function useEntry(port: WidgetDataPortLike, orgId: string, entryId: string | null) {
  return useQuery({
    queryKey: journalKeys.detail(orgId, entryId ?? ''),
    queryFn: () => port.request<EntryView>('journal.entry.get', { tenantId: orgId, entryId: entryId ?? '' }),
    enabled: !!entryId,
    // ui-std §7: polling is allowed while SUBMITTING, with a hard cap (react-query stops on success)
    refetchInterval: (q) => (q.state.data?.status === 'SUBMITTING' && q.state.dataUpdateCount < 10 ? 2_000 : false),
  });
}

export function usePostableAccounts(port: WidgetDataPortLike, orgId: string, bookId: string | null) {
  return useQuery({
    queryKey: journalKeys.accounts(orgId, bookId ?? ''),
    queryFn: () => port.request<GlAccountRow[]>('journal.entry.accounts', { tenantId: orgId, bookId: bookId ?? '' }),
    enabled: !!bookId,
    select: (rows) => rows.filter((a) => a.status === 'ACTIVE').sort((a, b) => a.code - b.code),
  });
}

export type JournalCommand =
  | { kind: 'create'; bookId: string; values: EntryValues }
  | { kind: 'submit' | 'approve' | 'check'; entryId: string };

export function useJournalCommand(port: WidgetDataPortLike, orgId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (c: JournalCommand): Promise<EntryView> => {
      if (c.kind === 'create') {
        const v = c.values;
        return port.command<EntryView>('journal.entry.create', {
          bookId: c.bookId, postingDate: v.postingDate, valueDate: v.postingDate,
          description: v.description, reference: v.reference || null,
          lines: v.lines.map((l) => ({ glAccountId: l.glAccountId, direction: l.direction, amount: toMinor(l.amount), memo: l.memo || null })),
        }, { tenantId: orgId });
      }
      const name = c.kind === 'submit' ? 'journal.entry.submit' : c.kind === 'approve' ? 'journal.entry.approve' : 'journal.entry.check';
      return port.command<EntryView>(name, {}, { tenantId: orgId, entryId: c.entryId });
    },
    retry: 0,
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: journalKeys.all(orgId) });
      void qc.invalidateQueries({ queryKey: ['approvals'] });
    },
  });
}
