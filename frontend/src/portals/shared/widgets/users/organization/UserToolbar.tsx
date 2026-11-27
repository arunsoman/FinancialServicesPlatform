import { clsx } from 'clsx';
import { Input } from '../../../ui/atoms';
import { SORT_OPTIONS, STATUS_FILTERS } from './model';
import type { SortId, StatusCountKey, StatusFilter } from './model';

export interface UserToolbarProps {
  query: string;
  onQuery: (q: string) => void;
  status: StatusFilter;
  onStatus: (s: StatusFilter) => void;
  /** whole-org counts from the server; undefined while the first page loads */
  counts: Record<StatusCountKey, number> | undefined;
  sort: SortId;
  onSort: (s: SortId) => void;
}

/**
 * Search, sort, status filter. ONE org per page — another org's users are reached by acting
 * as that org (Organization → Act as), never by a switcher here.
 */
export function UserToolbar({ query, onQuery, status, onStatus, counts, sort, onSort }: UserToolbarProps) {
  return (
    <div className="mb-4 grid gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <label className="relative min-w-[200px] flex-1">
          <span className="sr-only">Search users</span>
          <Input
            type="search"
            placeholder="Search name, username, email or role"
            value={query}
            onChange={(e) => onQuery(e.target.value)}
          />
        </label>
        <label className="flex items-center">
          <span className="sr-only">Sort users</span>
          <select className="fsp-field w-auto cursor-pointer" value={sort} onChange={(e) => onSort(e.target.value as SortId)}>
            {SORT_OPTIONS.map((o) => (
              <option key={o.id} value={o.id}>{o.label}</option>
            ))}
          </select>
        </label>
      </div>
      <div role="group" aria-label="Filter by status" className="flex flex-wrap gap-1.5">
        {STATUS_FILTERS.map((f) => (
          <button
            key={f.id}
            type="button"
            aria-pressed={status === f.id}
            onClick={() => onStatus(f.id)}
            className={clsx(
              'rounded-full border px-3 py-1 text-[12px] font-semibold transition-colors',
              status === f.id ? 'border-transparent bg-accent text-invert' : 'border-line text-dim hover:border-line-strong',
            )}
          >
            {f.label}
            {counts && (
              <span className={clsx('ml-1.5 tabular-nums', status === f.id ? 'opacity-80' : 'opacity-60')}>{counts[f.countKey]}</span>
            )}
          </button>
        ))}
      </div>
    </div>
  );
}
