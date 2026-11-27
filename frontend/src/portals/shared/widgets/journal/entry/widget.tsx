/**
 * journal.entry — maker + checker surfaces for journal entries. URL state: ?status= (filter),
 * ?entry= (selected), ?new=1 (maker form), ?book= (form book). Posting state is ALWAYS the
 * PostingStatePill; nothing claims "Posted" before FINALIZED.
 */
import { useCallback, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useWidgetRuntime } from '../../core/runtime';
import { useSession } from '../../../web-core/session-core';
import { Button, PageHeader, Panel } from '../../../ui/atoms';
import { NoticeBar } from '../../kit/Notice';
import type { Notice } from '../../kit/Notice';
import { StatePanel } from '../../kit/StatePanel';
import { pickBook, useBooks } from '../../kit/books';
import { isForbidden, toProblem } from '../../kit/problem';
import { EntryDetail } from './EntryDetail';
import { EntryForm } from './EntryForm';
import { EntryList } from './EntryList';
import { STATUS_FILTERS, useEntries, useEntry, useJournalCommand, usePostableAccounts } from './model';
import type { JournalCommand, JournalFilter } from './model';

const FILTER_LABEL: Record<JournalFilter, string> = {
  ALL: 'All', DRAFT: 'Drafts', PENDING_CHECKER: 'Awaiting approval', COMMIT_UNKNOWN: 'Outcome unknown', FINALIZED: 'Posted', REJECTED: 'Rejected',
};

export function JournalEntries() {
  const { port, context } = useWidgetRuntime();
  const session = useSession();
  const orgId = context.orgId ?? '';
  const [params, setParams] = useSearchParams();
  const statusParam = params.get('status') as JournalFilter | null;
  const status: JournalFilter = statusParam && STATUS_FILTERS.includes(statusParam) ? statusParam : 'ALL';
  const entryId = params.get('entry');
  const creating = params.get('new') === '1';
  const patch = (next: Record<string, string | null>) => setParams((p) => {
    const n = new URLSearchParams(p);
    for (const [k, val] of Object.entries(next)) {
      if (val === null || val === '') n.delete(k);
      else n.set(k, val);
    }
    return n;
  }, { replace: true });

  const entries = useEntries(port, orgId, status);
  const entry = useEntry(port, orgId, entryId);
  const books = useBooks(port, orgId);
  const book = pickBook((books.data ?? []).filter((b) => b.status === 'ACTIVE'), params.get('book'));
  const accountsBookId = entry.data?.bookId ?? book?.id ?? null;
  const accounts = usePostableAccounts(port, orgId, accountsBookId);
  const cmd = useJournalCommand(port, orgId);
  const [notice, setNotice] = useState<Notice | null>(null);
  const dismiss = useCallback(() => setNotice(null), []);
  const held = context.permissions;
  const canCreate = held.includes('je.create');
  const canApprove = held.includes('je.approve');

  const run = (c: JournalCommand) => cmd.mutate(c, {
    onSuccess: (res) => {
      if (c.kind === 'create') patch({ new: null, entry: res.entryId });
      // only FINALIZED / REJECTED may announce a money outcome (ui-std §9); others show inline state
      if (res.status === 'FINALIZED') setNotice({ id: Date.now(), kind: 'ok', text: 'Posted.' });
      else if (res.status === 'REJECTED') setNotice({ id: Date.now(), kind: 'bad', text: 'The ledger rejected this entry.' });
    },
  });

  if (entries.isPending) return <StatePanel kind="loading" title="Loading journal entries" />;
  if (entries.isError) {
    return isForbidden(entries.error)
      ? <StatePanel kind="forbidden" title="Not allowed here" hint="Journal entries need je.view." />
      : <StatePanel kind="error" title="Couldn’t load journal entries" hint={toProblem(entries.error).message} onRetry={() => void entries.refetch()} />;
  }
  const rows = entries.data ?? [];
  const problem = cmd.isError ? toProblem(cmd.error).message : null;

  return (
    <Panel className="p-4">
      <PageHeader
        title="Journal entries"
        subtitle="Balanced entries, four-eyes approved, posted to the ledger."
        actions={canCreate && !creating ? (
          <Button variant="primary" disabled={!book} title={!book ? 'No active book yet' : undefined} data-action="je.create"
            onClick={() => { cmd.reset(); patch({ new: '1', entry: null }); }}>New entry</Button>
        ) : undefined}
      />
      <NoticeBar notice={notice} onDismiss={dismiss} />
      <div role="tablist" aria-label="Filter entries" className="mb-3 flex flex-wrap gap-1">
        {STATUS_FILTERS.map((f) => (
          <button key={f} type="button" role="tab" aria-selected={status === f}
            className={status === f ? 'fsp-chip fsp-chip-accent' : 'fsp-chip'} onClick={() => patch({ status: f === 'ALL' ? null : f })}>
            {FILTER_LABEL[f]}
          </button>
        ))}
      </div>
      {creating && book && (
        <div className="mb-4">
          <EntryForm accounts={accounts.data ?? []} currency={book.currency} busy={cmd.isPending} problem={problem}
            onCancel={() => patch({ new: null })} onSubmit={(values) => run({ kind: 'create', bookId: book.id, values })} />
        </div>
      )}
      <div className="grid gap-4 md:grid-cols-[320px_1fr]">
        {rows.length === 0 ? <StatePanel kind="empty" title="No entries" hint={status === 'ALL' ? 'Create the first entry to start posting.' : 'Nothing in this state.'} />
          : <EntryList entries={rows} selectedId={entryId} onSelect={(id) => { cmd.reset(); patch({ entry: id, new: null }); }} />}
        {!entryId ? <StatePanel kind="empty" title="Select an entry" />
          : entry.isPending ? <StatePanel kind="loading" title="Loading entry" />
          : entry.isError ? <StatePanel kind="error" title="Couldn’t load the entry" hint={toProblem(entry.error).message} onRetry={() => void entry.refetch()} />
          : entry.data ? (
            <EntryDetail entry={entry.data} accounts={accounts.data ?? []} meId={session.user?.userId ?? null}
              canCreate={canCreate} canApprove={canApprove} busy={cmd.isPending} problem={creating ? null : problem}
              onSubmit={() => run({ kind: 'submit', entryId: entry.data.entryId })}
              onApprove={() => run({ kind: 'approve', entryId: entry.data.entryId })}
              onCheckStatus={() => run({ kind: 'check', entryId: entry.data.entryId })} />
          ) : null}
      </div>
    </Panel>
  );
}
