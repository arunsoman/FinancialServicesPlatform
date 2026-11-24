import { useState } from 'react';
import { clsx } from 'clsx';
import { Alert, Button } from '../../../ui/atoms';
import { Amount } from '../../../ui/Amount';
import { FormField } from '../../kit/FormField';
import { InlineConfirm } from '../../kit/InlineConfirm';
import { useWidgetRuntime } from '../../core/runtime';
import { METHOD_LABEL, payAmountError, toMajorText, toMinor } from './model';
import type { InvoiceView, PaymentMethod, PaymentOption } from './model';

export interface PayPanelProps {
  invoice: InvoiceView;
  options: PaymentOption[];
  optionsLoading: boolean;
  busy: boolean;
  onPay: (method: PaymentMethod, amountMinor: string) => void;
  onCancel: () => void;
}

const BLURB: Record<PaymentMethod, string> = {
  WALLET: 'Pays instantly from your e-money wallet.',
  BANK_TRANSFER: 'Pay from your bank with the reference below; the seller confirms when it arrives.',
  CARD: 'You enter card details on the payment provider’s secure page — never in this portal.',
};

/** The built-in payment options for one invoice (the server decides which are available). */
export function PayPanel({ invoice, options, optionsLoading, busy, onPay, onCancel }: PayPanelProps) {
  const wide = useWidgetRuntime().context.tier !== 'xs';
  const firstAvailable = options.find((o) => o.available)?.method ?? null;
  const [method, setMethod] = useState<PaymentMethod | null>(null);
  const chosen = method ?? firstAvailable;
  const [amount, setAmount] = useState(() => toMajorText(invoice.balanceMinor));
  const [amountError, setAmountError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const option = options.find((o) => o.method === chosen) ?? null;

  const check = () => {
    const err = payAmountError(amount, invoice.balanceMinor);
    setAmountError(err);
    return err === null;
  };
  const go = () => {
    const minor = toMinor(amount);
    if (chosen && minor) onPay(chosen, minor);
    setConfirming(false);
  };

  if (optionsLoading) return <p className="text-dim text-[13px]" aria-busy="true">Loading payment options…</p>;
  if (!options.length) return <Alert kind="warn">No payment options are available for this invoice.</Alert>;

  return (
    <section className="grid gap-3 rounded-xl border border-line-strong bg-raised p-3" aria-label="Pay this invoice">
      <div role="radiogroup" aria-label="Payment method" className={clsx('grid gap-2', wide && 'grid-cols-3')}>
        {options.map((o) => (
          <button key={o.method} type="button" role="radio" aria-checked={chosen === o.method} disabled={!o.available}
            data-method={o.method}
            className={clsx('rounded-xl border p-3 text-left disabled:opacity-50', chosen === o.method ? 'border-line-strong bg-panel' : 'border-line')}
            onClick={() => { setMethod(o.method); setConfirming(false); }}>
            <div className="text-[13px] font-semibold">{METHOD_LABEL[o.method]}</div>
            <div className="text-dim text-[12px]">{o.available ? BLURB[o.method] : o.reason}</div>
            {o.method === 'WALLET' && o.walletBalanceMinor && (
              <div className="mt-1 text-[12px]">Balance <Amount value={o.walletBalanceMinor} currency={invoice.currency} /></div>
            )}
          </button>
        ))}
      </div>

      <FormField label={`Amount (${invoice.currency})`} inputMode="decimal" className="tabular-nums" value={amount}
        hint="The full balance; lower it to pay part now." error={amountError ?? undefined}
        onBlur={check} onChange={(e) => setAmount(e.target.value)} />

      {option?.method === 'WALLET' && option.reason && <Alert kind="info">{option.reason}</Alert>}

      {option?.method === 'BANK_TRANSFER' && option.payee && (
        <dl className="grid gap-1 rounded-xl border border-line p-3 text-[13px]" aria-label="Bank transfer details">
          <div className="flex justify-between gap-2"><dt className="text-dim">Pay to</dt><dd className="font-semibold">{option.payee.name}</dd></div>
          <div className="flex justify-between gap-2"><dt className="text-dim">Bank</dt><dd>{option.payee.bankName}, account ending {option.payee.accountEnding}</dd></div>
          <div className="flex justify-between gap-2"><dt className="text-dim">Reference</dt><dd className="mono font-semibold">{option.reference}</dd></div>
          <dd className="text-dim text-[12px]">The full account number is on the invoice PDF and your seller’s statement.</dd>
        </dl>
      )}

      {confirming ? (
        <InlineConfirm title={`Pay ${amount} ${invoice.currency} from your wallet?`} confirmLabel="Pay now" tone="primary" busy={busy}
          onConfirm={go} onCancel={() => setConfirming(false)}>
          The money leaves your wallet now and the invoice balance drops straight away.
        </InlineConfirm>
      ) : (
        <div className="flex flex-wrap justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onCancel} disabled={busy}>Cancel</Button>
          <Button type="button" variant="primary" disabled={busy || !chosen} data-action="invoice.pay"
            onClick={() => { if (!check()) return; if (chosen === 'WALLET') setConfirming(true); else go(); }}>
            {busy ? 'Working…'
              : chosen === 'BANK_TRANSFER' ? 'I’ve made the transfer'
                : chosen === 'CARD' ? 'Continue to secure card page'
                  : 'Pay from wallet'}
          </Button>
        </div>
      )}
    </section>
  );
}
