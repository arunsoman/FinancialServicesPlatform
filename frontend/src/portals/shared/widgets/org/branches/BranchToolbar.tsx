import { clsx } from 'clsx';
import { X } from 'lucide-react';
import type { FormEvent, KeyboardEvent } from 'react';
import { Input } from '../../../ui/atoms';
import { SEARCH_MIN_LENGTH, SORT_OPTIONS, STATUS_FILTERS } from './model';
import type { SortId, StatusFilter } from './model';

export interface BranchToolbarProps {
  /** the search box text (local — the URL only gets the debounced value) */
  text: string;
  onText: (text: string) => void;
  /** Enter: search now, skipping the debounce */
  onSearchNow: () => void;
  /** × / Esc: clear at once */
  onClear: () => void;
  status: StatusFilter;
  onStatus: (s: StatusFilter) => void;
  sort: SortId;
  onSort: (s: SortId) => void;
}

/** Server-side search, sort and status filter for the branch list (ui-std §10.2, §10.5). */
export function BranchToolbar({ text, onText, onSearchNow, onClear, status, onStatus, sort, onSort }: BranchToolbarProps) {
  const trimmed = text.trim();
  const tooShort = trimmed.length > 0 && trimmed.length < SEARCH_MIN_LENGTH;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    onSearchNow();
  };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Escape' && text !== '') {
      e.preventDefault();
      onClear();
    }
  };
  return (
    <div className="mb-4 grid gap-3">
      <div className="flex flex-wrap items-start gap-2">
        <form role="search" className="min-w-[200px] flex-1" onSubmit={submit}>
          <label className="relative block">
            <span className="sr-only">Search branches</span>
            <Input
              type="text"
              inputMode="search"
              placeholder="Search branch name or code"
              aria-description="Searches branch names, codes and names awaiting approval"
              value={text}
              data-testid="branch-search"
              onChange={(e) => onText(e.target.value)}
              onKeyDown={onKey}
            />
            {text !== '' && (
              <button
                type="button"
                onClick={onClear}
                aria-label="Clear search"
                className="text-dim absolute right-2 top-1/2 -translate-y-1/2 rounded p-1 hover:text-current"
              >
                <X size={14} aria-hidden />
              </button>
            )}
          </label>
          {tooShort && (
            <p className="text-dim mt-1 text-[12px]" data-testid="branch-search-hint">
              Type at least {SEARCH_MIN_LENGTH} characters to search.
            </p>
          )}
        </form>
        <label className="flex items-center">
          <span className="sr-only">Sort branches</span>
          <select
            className="fsp-field w-auto cursor-pointer"
            value={sort}
            data-testid="branch-sort"
            onChange={(e) => onSort(e.target.value as SortId)}
          >
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
            data-testid={`branch-filter-${f.id.toLowerCase()}`}
            onClick={() => onStatus(f.id)}
            className={clsx(
              'rounded-full border px-3 py-1 text-[12px] font-semibold transition-colors',
              status === f.id ? 'border-transparent bg-accent text-invert' : 'border-line text-dim hover:border-line-strong',
            )}
          >
            {f.label}
          </button>
        ))}
      </div>
    </div>
  );
}
