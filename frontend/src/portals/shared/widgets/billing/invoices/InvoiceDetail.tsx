import { useState } from 'react';
import { Alert, Button, Chip, KvPair } from '../../../ui/atoms';
import { Amount } from '../../../ui/Amount';
import { InlineConfirm } from '../../kit/InlineConfirm';
import { useWidgetRuntime } from '../../core/runtime';
import { TextAreaField } from '../../kit/TextAreaField';
import { PayPanel } from './PayPanel';
import {
  METHOD_LABEL, PAYMENT_LABEL, PAYMENT_TONE, STATUS_LABEL, STATUS_TONE, dueState, invoiceActions, termsLabel,
} from './model';
import type { InvoiceView, PaymentMethod, PaymentOption } from './model';

export interface InvoiceDetailProps {
  invoice: InvoiceView;
  me: { orgId: string; held: readonly string[]; platform: boolean };
  today: string;
  options: PaymentOption[];
  optionsLoading: boolean;
  paying: boolean;
  onStartPay: () => void;
  onStopPay: () => void;
  busy: boolean;
  problem: string | null;
  onIssue: () => void;
  onPay: (method: PaymentMethod, amountMinor: string) => void;
  onConfirm: (paymentId: string) => void;
  onVoid: (reason: string) => void;
}

/** One invoice: parties, terms, lines, totals, payments — and what this person may do next. */
export function InvoiceDetail(p: InvoiceDetailProps) {
  const { invoice: inv, today } = p;
  const wide = useWidgetRuntime().context.tier !== 'xs';
  const can = invoiceActions(inv, p.me);
  const due = dueState(inv, today);
  const [voiding, setVoiding] = useState(false);
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState<string | null>(null);

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <Chip tone={STATUS_TONE[inv.status]}>{STATUS_LABEL[inv.status]}</Chip>
        {(inv.status === 'ISSUED' || inv.status === 'PARTIALLY_PAID') && <Chip tone={due.tone}>{due.label}</Chip>}
      </div>
      {inv.status === 'VOID_PENDING' && (
        <Alert kind="warn">Voiding this invoice waits for a second person to approve it (four-eyes). It stays payable until then.</Alert>
      )}

      <div className={wide ? 'grid grid-cols-2 gap-3' : 'grid gap-3'}>
        <KvPair label="From">{inv.seller.name}</KvPair>
        <KvPair label="Bill to">{inv.buyer.name}</KvPair>
        <KvPair label="Issue date">{inv.issueDate ?? `${inv.plannedIssueDate} (when issued)`}</KvPair>
        <KvPair label="Terms">{termsLabel(inv.creditDays)}</KvPair>
        <KvPair label="Due date">{inv.dueDate ?? '—'}</KvPair>
        {inv.reference && <KvPair label="Reference" mono>{inv.reference}</KvPair>}
      </div>

      <div className="overflow-x-auto rounded-xl border border-line">
        <table className="w-full min-w-[520px] text-[13px]">
          <thead className="text-dim text-left text-[11px] uppercase">
            <tr><th className="p-2">Item</th><th className="p-2 text-right">Qty</th><th className="p-2 text-right">Unit price</th><th className="p-2 text-right">VAT</th><th className="p-2 text-right">Total</th></tr>
          </thead>
          <tbody className="divide-y divide-line">
            {inv.lines.map((l) => (
              <tr key={l.lineNo}>
                <td className="p-2">{l.description}</td>
                <td className="p-2 text-right tabular-nums">{l.quantity}</td>
                <td className="p-2 text-right"><Amount value={l.unitPriceMinor} currency={inv.currency} /></td>
                <td className="p-2 text-right"><Amount value={l.taxMinor} currency={inv.currency} /></td>
                <td className="p-2 text-right"><Amount value={l.totalMinor} currency={inv.currency} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <dl className="ml-auto grid w-full max-w-xs gap-1 text-[13px]">
        <div className="flex justify-between"><dt className="text-dim">Subtotal</dt><dd><Amount value={inv.subtotalMinor} currency={inv.currency} /></dd></div>
        <div className="flex justify-between"><dt className="text-dim">VAT</dt><dd><Amount value={inv.taxMinor} currency={inv.currency} /></dd></div>
        <div className="flex justify-between font-semibold"><dt>Total</dt><dd><Amount value={inv.totalMinor} currency={inv.currency} /></dd></div>
        <div className="flex justify-between"><dt className="text-dim">Paid</dt><dd><Amount value={inv.paidMinor} currency={inv.currency} /></dd></div>
        <div className="flex justify-between border-t border-line pt-1 text-base font-bold"><dt>Balance due</dt><dd><Amount value={inv.balanceMinor} currency={inv.currency} /></dd></div>
      </dl>

      {inv.payments.length > 0 && (
        <section aria-label="Payments">
          <h3 className="fsp-eyebrow mb-1">Payments</h3>
          <ul className="grid gap-1.5">
            {inv.payments.map((pay) => (
              <li key={pay.paymentId} className="flex flex-wrap items-center gap-2 rounded-xl border border-line px-3 py-2 text-[13px]">
                <span className="font-semibold">{METHOD_LABEL[pay.method]}</span>
                <Amount value={pay.amountMinor} currency={inv.currency} />
                <Chip tone={PAYMENT_TONE[pay.status]}>{PAYMENT_LABEL[pay.status]}</Chip>
                {pay.reference && <span className="mono text-dim text-[12px]">{pay.reference}</span>}
                <span className="grow" />
                {pay.method === 'CARD' && pay.status === 'PENDING' && pay.redirectUrl && (
                  <a className="underline" href={pay.redirectUrl}>Open the secure card page</a>
                )}
                {can.confirm.some((c) => c.paymentId === pay.paymentId) && (
                  <Button variant="primary" disabled={p.busy} data-action="invoice.collect" onClick={() => p.onConfirm(pay.paymentId)}>
                    Confirm money received
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}

      {p.problem && <Alert kind="bad">{p.problem}</Alert>}

      {p.paying && can.pay ? (
        <PayPanel invoice={inv} options={p.options} optionsLoading={p.optionsLoading} busy={p.busy} onPay={p.onPay} onCancel={p.onStopPay} />
      ) : voiding ? (
        <InlineConfirm title={inv.status === 'DRAFT' ? 'Discard this draft?' : 'Void this invoice?'} confirmLabel={inv.status === 'DRAFT' ? 'Discard draft' : 'Request void'}
          tone="danger" busy={p.busy}
          onCancel={() => { setVoiding(false); setReasonError(null); }}
          onConfirm={() => {
            if (reason.trim().length < 3) { setReasonError('Say why, so the approver and the buyer know'); return; }
            p.onVoid(reason.trim());
            setVoiding(false);
          }}>
          {inv.status === 'DRAFT' ? 'The draft is cancelled; nothing was sent to the buyer.'
            : 'The receivable is reversed once a second person approves. The buyer sees it as void.'}
          <TextAreaField label="Reason" rows={2} value={reason} error={reasonError ?? undefined} onChange={(e) => setReason(e.target.value)} />
        </InlineConfirm>
      ) : (
        <div className="flex flex-wrap justify-end gap-2">
          {can.void && <Button variant="danger" disabled={p.busy} data-action="invoice.void" onClick={() => setVoiding(true)}>{inv.status === 'DRAFT' ? 'Discard draft' : 'Void'}</Button>}
          {can.issue && <Button variant="primary" disabled={p.busy} data-action="invoice.issue" onClick={p.onIssue}>{p.busy ? 'Issuing…' : 'Issue invoice'}</Button>}
          {can.pay && <Button variant="primary" disabled={p.busy} data-action="invoice.pay" onClick={p.onStartPay}>Pay</Button>}
        </div>
      )}
    </div>
  );
}
