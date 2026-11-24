import { clsx } from 'clsx';
import { Chip } from '../../../ui/atoms';
import { Amount } from '../../../ui/Amount';
import { STATUS_LABEL, STATUS_TONE, dueState } from './model';
import type { Direction, InvoiceSummary } from './model';

export interface InvoiceListProps {
  invoices: InvoiceSummary[];
  direction: Direction;
  today: string;
  selectedId: string | null;
  onSelect: (id: string) => void;
}

/** Two-line invoice rows: number · counterparty · status, then due state · balance. Fits a phone. */
export function InvoiceList({ invoices, direction, today, selectedId, onSelect }: InvoiceListProps) {
  return (
    <ul className="grid grid-cols-[minmax(0,1fr)] content-start gap-1.5" aria-label="Invoices">
      {invoices.map((inv) => {
        const due = dueState(inv, today);
        const party = direction === 'SALES' ? inv.buyer.name : direction === 'PURCHASES' ? inv.seller.name : `${inv.seller.name} → ${inv.buyer.name}`;
        return (
          <li key={inv.invoiceId}>
            <button type="button" onClick={() => onSelect(inv.invoiceId)} aria-current={selectedId === inv.invoiceId ? 'true' : undefined}
              data-invoice={inv.number ?? 'draft'}
              className={clsx('grid w-full grid-cols-[minmax(0,1fr)] gap-0.5 rounded-xl border px-3 py-2 text-left',
                selectedId === inv.invoiceId ? 'border-line-strong bg-raised' : 'border-line hover:bg-raised')}>
              <span className="flex min-w-0 items-center gap-2">
                <span className="mono shrink-0 text-[12px] font-semibold">{inv.number ?? 'Draft'}</span>
                <span className="min-w-0 flex-1 truncate text-[13px] font-semibold">{party}</span>
                <Chip tone={STATUS_TONE[inv.status]}>{STATUS_LABEL[inv.status]}</Chip>
              </span>
              <span className="flex min-w-0 items-center justify-between gap-2 text-[12px]">
                <span className={clsx(due.tone === 'bad' && 'text-status-danger', due.tone === 'warn' && 'text-status-warning')}>{due.label}</span>
                <span className="font-semibold"><span className="text-dim font-normal">Balance </span><Amount value={inv.balanceMinor} currency={inv.currency} /></span>
              </span>
            </button>
          </li>
        );
      })}
    </ul>
  );
}
