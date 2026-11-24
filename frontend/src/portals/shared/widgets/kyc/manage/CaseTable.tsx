import { clsx } from 'clsx';
import { CaseStatusChip, RiskChip } from './CaseChips';
import { formatDate, isStalled, relativeTime, reviewDue } from './model';
import type { CaseSummary, ListQuery, SortKey } from './model';

export interface CaseTableProps {
  rows: CaseSummary[];
  /** compact = card list (narrow containers); otherwise a table. */
  compact: boolean;
  query: ListQuery;
  selectedId: string | null;
  onSort: (key: SortKey) => void;
  onOpen: (row: CaseSummary) => void;
}

const SUB_LABEL: Record<NonNullable<CaseSummary['subType']>, string> = {
  MANUFACTURER: 'Manufacturer', DISTRIBUTOR: 'Distributor', DEALER: 'Dealer', WHOLESALER: 'Wholesaler', MERCHANT: 'Merchant',
};

function ReviewDate({ iso }: { iso: string | null }) {
  const due = reviewDue(iso);
  return (
    <span className={clsx('tabular-nums', due === 'overdue' && 'font-semibold text-status-danger', due === 'soon' && 'text-status-warning')}>
      {formatDate(iso)}
      {due === 'overdue' && <span className="sr-only"> (overdue)</span>}
    </span>
  );
}

function Org({ row }: { row: CaseSummary }) {
  return (
    <span className="block min-w-0">
      <span className="block truncate font-semibold">{row.legalName}</span>
      <span className="text-dim block truncate text-[12px]">
        <span className="mono">{row.caseNo}</span> · {row.subType ? SUB_LABEL[row.subType] : 'Customer'}
        {row.dbaName ? ` · t/a ${row.dbaName}` : ''}
      </span>
    </span>
  );
}

interface Col {
  label: string;
  sort?: SortKey;
  className?: string;
}
const COLS: Col[] = [
  { label: 'Organization', sort: 'legalName' },
  { label: 'Status', sort: 'status' },
  { label: 'Risk', sort: 'riskRating' },
  { label: 'Submitted', sort: 'submittedAt', className: 'text-right' },
  { label: 'Next review', sort: 'nextReviewDate', className: 'text-right' },
  { label: 'Submitted by' },
];

/**
 * One page of cases, as a table with server-side sortable headers (wide) or tappable cards
 * (narrow). Rows open the case drawer.
 */
export function CaseTable({ rows, compact, query, selectedId, onSort, onOpen }: CaseTableProps) {
  if (compact) {
    return (
      <ul className="grid gap-2">
        {rows.map((r) => (
          <li key={r.caseId}>
            <button
              type="button"
              onClick={() => onOpen(r)}
              aria-current={selectedId === r.caseId}
              className={clsx(
                'w-full rounded-xl border bg-sunken p-3 text-left transition-colors hover:border-line-strong',
                selectedId === r.caseId ? 'border-line-strong' : 'border-line',
              )}
            >
              <Org row={r} />
              <span className="mt-2 flex flex-wrap items-center gap-1.5">
                <CaseStatusChip status={r.status} stalled={isStalled(r)} />
                <RiskChip risk={r.riskRating} />
              </span>
              <span className="text-dim mt-2 block text-[12px]">
                {r.submittedAt ? `Submitted ${relativeTime(r.submittedAt)}` : 'Draft'}
                {r.makerName ? ` by ${r.makerName}` : ''}
              </span>
            </button>
          </li>
        ))}
      </ul>
    );
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full border-separate border-spacing-0 text-sm">
        <thead>
          <tr className="text-dim text-left text-[10.5px] font-bold uppercase tracking-[.14em]">
            {COLS.map((c) => {
              const active = c.sort && query.sort === c.sort;
              const ariaSort = active ? (query.dir === 'asc' ? 'ascending' : 'descending') : c.sort ? 'none' : undefined;
              return (
                <th key={c.label} scope="col" aria-sort={ariaSort} className={clsx('whitespace-nowrap border-b border-line px-3 py-2 font-bold', c.className)}>
                  {c.sort ? (
                    <button
                      type="button"
                      onClick={() => onSort(c.sort as SortKey)}
                      className={clsx('inline-flex items-center gap-1 uppercase tracking-[.14em] hover:text-[var(--color-text)]', active && 'text-[var(--color-text)]')}
                    >
                      {c.label}
                      <span aria-hidden="true" className={clsx('text-[10px]', !active && 'opacity-30')}>
                        {active && query.dir === 'asc' ? '▲' : '▼'}
                      </span>
                    </button>
                  ) : (
                    c.label
                  )}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr
              key={r.caseId}
              tabIndex={0}
              aria-selected={selectedId === r.caseId}
              onClick={() => onOpen(r)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  onOpen(r);
                }
              }}
              className={clsx(
                'cursor-pointer outline-none transition-colors hover:bg-raised focus-visible:bg-raised',
                selectedId === r.caseId && 'bg-raised',
              )}
            >
              <td className="max-w-[300px] border-b border-line px-3 py-2.5"><Org row={r} /></td>
              <td className="border-b border-line px-3 py-2.5"><CaseStatusChip status={r.status} stalled={isStalled(r)} /></td>
              <td className="whitespace-nowrap border-b border-line px-3 py-2.5"><RiskChip risk={r.riskRating} /></td>
              <td className="text-dim whitespace-nowrap border-b border-line px-3 py-2.5 text-right text-xs tabular-nums" title={r.submittedAt ?? undefined}>
                {r.submittedAt ? relativeTime(r.submittedAt) : '—'}
              </td>
              <td className="whitespace-nowrap border-b border-line px-3 py-2.5 text-right text-xs"><ReviewDate iso={r.nextReviewDate} /></td>
              <td className="text-dim max-w-[160px] truncate border-b border-line px-3 py-2.5 text-xs">{r.makerName ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
