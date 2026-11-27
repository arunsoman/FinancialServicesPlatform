import { Button } from '../../ui/atoms';

export interface PagerProps {
  /** 0-indexed */
  page: number;
  size: number;
  /** rows on this page */
  shown: number;
  totalElements: number;
  hasNext: boolean;
  sizes: readonly number[];
  busy?: boolean;
  noun?: string;
  onPage: (page: number) => void;
  onSize: (size: number) => void;
}

/**
 * Pager for a server page with totals (AGENTS §2.7 envelope): "21–30 of 30 users",
 * rows-per-page choice, previous / next. Hidden entirely when everything fits on one page
 * at the smallest size.
 */
export function Pager({ page, size, shown, totalElements, hasNext, sizes, busy, noun = 'rows', onPage, onSize }: PagerProps) {
  if (page === 0 && !hasNext && totalElements <= (sizes[0] ?? size)) return null;
  const from = shown > 0 ? page * size + 1 : 0;
  const to = page * size + shown;
  return (
    <nav aria-label="Pages" className="mt-3 flex flex-wrap items-center gap-2 border-t border-line pt-3 text-[13px]">
      <span className="text-dim tabular-nums" aria-live="polite">
        {shown > 0 ? `${from}–${to} of ${totalElements} ${noun}` : `Page ${page + 1} is empty`}
      </span>
      <span className="flex-1" />
      <label className="text-dim flex items-center gap-2">
        Rows per page
        <select className="fsp-field w-auto cursor-pointer py-1" value={size} onChange={(e) => onSize(Number(e.target.value))}>
          {sizes.map((s) => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
      </label>
      <Button variant="ghost" disabled={page === 0 || busy} onClick={() => onPage(page - 1)}>
        ‹ Previous
      </Button>
      <Button variant="ghost" disabled={!hasNext || busy} onClick={() => onPage(page + 1)}>
        Next ›
      </Button>
    </nav>
  );
}
