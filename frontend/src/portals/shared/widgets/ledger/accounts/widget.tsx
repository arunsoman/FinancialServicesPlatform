/**
 * ledger.accounts — the chart of accounts of one book (?book= in the URL). An empty active book
 * offers "Provision default chart" (gl.account.create; provisioning is provision-once and
 * rerun-safe in core, vision §4.5).
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { useWidgetRuntime } from '../../core/runtime';
import { Alert, Button, Chip, PageHeader, Panel, StatusBadge } from '../../../ui/atoms';
import { BookPicker } from '../../kit/BookPicker';
import { StatePanel } from '../../kit/StatePanel';
import { ledgerKeys, pickBook, useBooks } from '../../kit/books';
import type { GlAccountRow } from '../../kit/books';
import { isForbidden, toProblem } from '../../kit/problem';

export function LedgerAccounts() {
  const { port, context } = useWidgetRuntime();
  const orgId = context.orgId ?? '';
  const [params, setParams] = useSearchParams();
  const books = useBooks(port, orgId);
  const book = pickBook(books.data ?? [], params.get('book'));
  const qc = useQueryClient();
  const accounts = useQuery({
    queryKey: ledgerKeys.accounts(orgId, book?.id ?? ''),
    queryFn: () => port.request<GlAccountRow[]>('ledger.accounts.list', { tenantId: orgId, bookId: book?.id ?? '' }),
    enabled: !!book,
  });
  const provision = useMutation({
    mutationFn: () => port.command<GlAccountRow[]>('ledger.accounts.provision', {}, { tenantId: orgId, bookId: book?.id ?? '' }),
    retry: 0,
    onSettled: () => void qc.invalidateQueries({ queryKey: ledgerKeys.accounts(orgId, book?.id ?? '') }),
  });
  const canCreate = context.permissions.includes('gl.account.create');

  if (books.isPending) return <StatePanel kind="loading" title="Loading books" />;
  if (books.isError) {
    return isForbidden(books.error)
      ? <StatePanel kind="forbidden" title="Not allowed here" hint="The chart of accounts needs gl.account.view." />
      : <StatePanel kind="error" title="Couldn’t load books" hint={toProblem(books.error).message} onRetry={() => void books.refetch()} />;
  }
  if (!book) return <StatePanel kind="empty" title="No books yet" hint="Books open when the organization is verified." />;

  const rows = accounts.data ?? [];
  return (
    <Panel className="p-4">
      <PageHeader title="Chart of accounts" subtitle={`${book.name} · ${book.currency}`}
        actions={<div className="w-64"><BookPicker books={books.data ?? []} value={book.id}
          onChange={(id) => setParams((p) => { const n = new URLSearchParams(p); n.set('book', id); return n; }, { replace: true })} /></div>} />
      {book.status !== 'ACTIVE' && <div className="mb-3"><Alert kind="warn">This book is {book.status.toLowerCase()}; it opens when the organization is verified.</Alert></div>}
      {provision.isError && <div className="mb-3"><Alert kind="bad">{toProblem(provision.error).message}</Alert></div>}
      {accounts.isPending ? <StatePanel kind="loading" title="Loading accounts" />
        : accounts.isError ? <StatePanel kind="error" title="Couldn’t load accounts" hint={toProblem(accounts.error).message} onRetry={() => void accounts.refetch()} />
        : rows.length === 0 ? (
          <div className="grid gap-3">
            <StatePanel kind="empty" title="No accounts in this book" hint="Provision the default chart to start posting." />
            {canCreate && book.status === 'ACTIVE' && (
              <div className="flex justify-center">
                <Button variant="primary" disabled={provision.isPending} data-action="coa.provision" onClick={() => provision.mutate()}>
                  {provision.isPending ? 'Provisioning…' : 'Provision default chart'}
                </Button>
              </div>
            )}
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full border-separate border-spacing-0 text-sm">
              <thead>
                <tr className="text-dim text-left text-[11px] uppercase tracking-[.12em]">
                  <th className="border-b border-line py-2 pr-3 font-bold">Code</th>
                  <th className="border-b border-line py-2 pr-3 font-bold">Name</th>
                  <th className="border-b border-line py-2 pr-3 font-bold">Type</th>
                  <th className="border-b border-line py-2 pr-3 font-bold">Normal side</th>
                  <th className="border-b border-line py-2 font-bold">Status</th>
                </tr>
              </thead>
              <tbody>
                {[...rows].sort((a, b) => a.code - b.code).map((a) => (
                  <tr key={a.id}>
                    <td className="mono border-b border-line py-2 pr-3 tabular-nums">{a.code}</td>
                    <td className="border-b border-line py-2 pr-3">{a.name}</td>
                    <td className="border-b border-line py-2 pr-3"><Chip>{a.accountType.toLowerCase()}</Chip></td>
                    <td className="border-b border-line py-2 pr-3 text-[12px]">{a.normalBalance.toLowerCase()}</td>
                    <td className="border-b border-line py-2"><StatusBadge status={a.status} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
    </Panel>
  );
}
