import { clsx } from 'clsx';
import { BranchStatusChip } from './BranchStatusChip';
import { approvedByText, dateTime } from './model';
import type { BranchRow } from './model';

export interface BranchListProps {
  branches: BranchRow[];
  /** compact = tappable cards (phone / narrow container); otherwise a table */
  compact: boolean;
  selectedId: string | null;
  onOpen: (branch: BranchRow) => void;
}

/** The proposed new name under the current one, while a rename waits for its checker. */
function PendingRename({ branch }: { branch: BranchRow }) {
  if (branch.pending?.operation !== 'BRANCH_UPDATE' || !branch.pending.proposedName) return null;
  return <span className="text-dim block truncate text-[12px]">→ {branch.pending.proposedName}</span>;
}

/** Branches as a table (wide) or cards (narrow). Rows open the branch drawer. */
export function BranchList({ branches, compact, selectedId, onOpen }: BranchListProps) {
  if (compact) {
    return (
      <ul className="grid grid-cols-1 gap-2" aria-label="Branches">
        {branches.map((b) => (
          <li key={b.id} className="min-w-0">
            <button
              type="button"
              onClick={() => onOpen(b)}
              aria-current={selectedId === b.id}
              data-testid={`branch-row-${b.code}`}
              className={clsx(
                'w-full rounded-xl border bg-sunken p-3 text-left transition-colors hover:border-line-strong',
                selectedId === b.id ? 'border-line-strong' : 'border-line',
              )}
            >
              <span className="flex flex-wrap items-start justify-between gap-2">
                <span className="min-w-0 flex-1 basis-40">
                  <span className="block truncate text-sm font-semibold">{b.name}</span>
                  <PendingRename branch={b} />
                  <span className="text-dim mono block text-[11px]">{b.code}</span>
                </span>
                <BranchStatusChip branch={b} />
              </span>
              <span className="text-dim mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-[12px]">
                <span>Created by <span className="text-current">{b.makerName}</span></span>
                <span>Approved by <span className="text-current">{approvedByText(b)}</span></span>
                <span className="col-span-2 tabular-nums">{dateTime(b.createdAt)}</span>
              </span>
            </button>
          </li>
        ))}
      </ul>
    );
  }

  // inline-size containment: the table scrolls inside this box and never widens the widget —
  // otherwise its no-wrap min-content width would feed the tier ResizeObserver a desktop width
  return (
    <div className="w-full overflow-x-auto [contain:inline-size]">
      <table className="w-full border-separate border-spacing-0 text-sm" aria-label="Branches">
        <thead>
          <tr className="text-dim text-left text-[10.5px] font-bold uppercase tracking-[.14em]">
            <th scope="col" className="border-b border-line px-3 py-2 font-bold">Branch</th>
            <th scope="col" className="border-b border-line px-3 py-2 font-bold">Status</th>
            <th scope="col" className="border-b border-line px-3 py-2 font-bold">Created by</th>
            <th scope="col" className="border-b border-line px-3 py-2 font-bold">Approved by</th>
            <th scope="col" className="border-b border-line px-3 py-2 text-right font-bold">Created</th>
          </tr>
        </thead>
        <tbody>
          {branches.map((b) => (
            <tr
              key={b.id}
              tabIndex={0}
              aria-selected={selectedId === b.id}
              data-testid={`branch-row-${b.code}`}
              onClick={() => onOpen(b)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  onOpen(b);
                }
              }}
              className={clsx(
                'cursor-pointer outline-none transition-colors hover:bg-raised focus-visible:bg-raised',
                selectedId === b.id && 'bg-raised',
              )}
            >
              <td className="max-w-[280px] border-b border-line px-3 py-2.5">
                <span className="block truncate font-semibold">{b.name}</span>
                <PendingRename branch={b} />
                <span className="text-dim mono block text-[11px]">{b.code}</span>
              </td>
              <td className="border-b border-line px-3 py-2.5"><BranchStatusChip branch={b} /></td>
              <td className="whitespace-nowrap border-b border-line px-3 py-2.5">{b.makerName}</td>
              <td className={clsx('whitespace-nowrap border-b border-line px-3 py-2.5', !b.checkerName && 'text-dim')}>
                {approvedByText(b)}
              </td>
              <td className="text-dim whitespace-nowrap border-b border-line px-3 py-2.5 text-right text-xs tabular-nums">
                {dateTime(b.createdAt)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
