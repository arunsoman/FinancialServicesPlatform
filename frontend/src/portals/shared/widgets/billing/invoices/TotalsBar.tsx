import { Amount } from '../../../ui/Amount';
import { useWidgetRuntime } from '../../core/runtime';
import type { InvoiceTotals } from './model';

export interface TotalsBarProps {
  totals: InvoiceTotals;
  /** "Owed to you" (sales) / "You owe" (purchases) / "Outstanding" (platform) */
  outstandingLabel: string;
}

/** Server-computed totals (never summed here): outstanding, overdue, due within 7 days. */
export function TotalsBar({ totals, outstandingLabel }: TotalsBarProps) {
  const wide = useWidgetRuntime().context.tier !== 'xs';
  const cells = [
    { label: outstandingLabel, value: totals.outstandingMinor, hint: `${totals.openCount} open`, tone: '' },
    { label: 'Overdue', value: totals.overdueMinor, hint: `${totals.overdueCount} invoice${totals.overdueCount === 1 ? '' : 's'}`, tone: totals.overdueCount > 0 ? 'text-status-danger' : '' },
    { label: 'Due in 7 days', value: totals.dueSoonMinor, hint: 'on 30-day terms by default', tone: '' },
  ];
  return (
    <dl className={wide ? 'mb-3 grid grid-cols-3 gap-2' : 'mb-3 grid gap-2'} aria-label="Invoice totals">
      {cells.map((c) => (
        <div key={c.label} className="rounded-xl border border-line bg-raised px-3 py-2">
          <dt className="fsp-eyebrow">{c.label}</dt>
          <dd className={`text-lg font-bold ${c.tone}`}><Amount value={c.value} currency={totals.currency} /></dd>
          <dd className="text-dim text-[12px]">{c.hint}</dd>
        </div>
      ))}
    </dl>
  );
}
