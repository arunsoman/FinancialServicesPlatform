import { clsx } from 'clsx';
import { Amount } from '../../../ui/Amount';
import { PostingStatePill } from '../../../ui/PostingStatePill';
import type { EntryView } from './model';

export interface EntryListProps {
  entries: EntryView[];
  selectedId: string | null;
  onSelect: (id: string) => void;
}

export function EntryList({ entries, selectedId, onSelect }: EntryListProps) {
  return (
    <ul className="grid gap-1.5" aria-label="Journal entries">
      {entries.map((e) => (
        <li key={e.entryId}>
          <button type="button" onClick={() => onSelect(e.entryId)} aria-current={selectedId === e.entryId ? 'true' : undefined}
            className={clsx('w-full rounded-xl border px-3 py-2 text-left', selectedId === e.entryId ? 'border-line-strong bg-raised' : 'border-line hover:bg-raised')}>
            <div className="flex items-center justify-between gap-2">
              <span className="truncate text-[13px] font-semibold">{e.description}</span>
              <PostingStatePill state={e.status} />
            </div>
            <div className="text-dim mt-0.5 flex justify-between gap-2 text-[12px]">
              <span className="tabular-nums">{e.postingDate}{e.reference ? ` · ${e.reference}` : ''}</span>
              <Amount value={e.totalDebits} currency={e.currency} />
            </div>
          </button>
        </li>
      ))}
    </ul>
  );
}
