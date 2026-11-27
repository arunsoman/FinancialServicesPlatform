/**
 * period.control — accounting periods of one book and their state. Core exposes the period LIST
 * only (ledger.period.list); open/close transitions arrive with the period-close slice
 * (vision §5.9), so this screen states that instead of offering controls it cannot honour.
 */
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { useWidgetRuntime } from '../../core/runtime';
import { Alert, Chip, PageHeader, Panel } from '../../../ui/atoms';
import { BookPicker } from '../../kit/BookPicker';
import { StatePanel } from '../../kit/StatePanel';
import { ledgerKeys, pickBook, useBooks } from '../../kit/books';
import { isForbidden, toProblem } from '../../kit/problem';

interface PeriodRow {
  id: string;
  fiscalYear: number;
  periodNo: number;
  startDate: string;
  endDate: string;
  status: 'OPEN' | 'SOFT_CLOSED' | 'HARD_CLOSED';
}

const TONE: Record<PeriodRow['status'], 'ok' | 'warn' | 'neutral'> = { OPEN: 'ok', SOFT_CLOSED: 'warn', HARD_CLOSED: 'neutral' };

export function PeriodControl() {
  const { port, context } = useWidgetRuntime();
  const orgId = context.orgId ?? '';
  const [params, setParams] = useSearchParams();
  const books = useBooks(port, orgId);
  const book = pickBook(books.data ?? [], params.get('book'));
  const periods = useQuery({
    queryKey: ledgerKeys.periods(orgId, book?.id ?? ''),
    queryFn: () => port.request<PeriodRow[]>('period.control.list', { tenantId: orgId, bookId: book?.id ?? '' }),
    enabled: !!book,
  });

  if (books.isPending) return <StatePanel kind="loading" title="Loading books" />;
  if (books.isError) {
    return isForbidden(books.error)
      ? <StatePanel kind="forbidden" title="Not allowed here" hint="Accounting periods need gl.account.view." />
      : <StatePanel kind="error" title="Couldn’t load books" hint={toProblem(books.error).message} onRetry={() => void books.refetch()} />;
  }
  if (!book) return <StatePanel kind="empty" title="No books yet" />;
  const rows = [...(periods.data ?? [])].sort((a, b) => b.fiscalYear - a.fiscalYear || b.periodNo - a.periodNo);
  return (
    <Panel className="p-4">
      <PageHeader title="Accounting periods" subtitle={`${book.name} · ${book.currency}`}
        actions={<div className="w-64"><BookPicker books={books.data ?? []} value={book.id}
          onChange={(id) => setParams((p) => { const n = new URLSearchParams(p); n.set('book', id); return n; }, { replace: true })} /></div>} />
      <div className="mb-3"><Alert kind="info">Opening and closing periods is not available through the API yet; this view shows their current state.</Alert></div>
      {periods.isPending ? <StatePanel kind="loading" title="Loading periods" />
        : periods.isError ? <StatePanel kind="error" title="Couldn’t load periods" hint={toProblem(periods.error).message} onRetry={() => void periods.refetch()} />
        : rows.length === 0 ? <StatePanel kind="empty" title="No periods in this book" />
        : (
          <ul className="grid gap-2">
            {rows.map((p) => (
              <li key={p.id} className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-line px-3 py-2">
                <span className="tabular-nums text-sm font-semibold">FY{p.fiscalYear} · P{String(p.periodNo).padStart(2, '0')}</span>
                <span className="text-dim tabular-nums text-[12px]">{p.startDate} → {p.endDate}</span>
                <Chip tone={TONE[p.status]}>{p.status.replace('_', ' ').toLowerCase()}</Chip>
              </li>
            ))}
          </ul>
        )}
    </Panel>
  );
}
