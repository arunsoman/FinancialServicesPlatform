import { useState } from 'react';
import type { FormEvent } from 'react';
import { Alert, Button } from '../../../ui/atoms';
import { Amount } from '../../../ui/Amount';
import { FormField } from '../../kit/FormField';
import { SelectField } from '../../kit/SelectField';
import { TextAreaField } from '../../kit/TextAreaField';
import { useWidgetRuntime } from '../../core/runtime';
import { InvoiceLineEditor } from './InvoiceLineEditor';
import { CREDIT_TERMS, CURRENCIES, blankInvoice, blankLine, dueDateFor, fieldErrors, invoiceSchema, previewTotals } from './model';
import type { Counterparty, InvoiceValues, LineValues } from './model';

export interface InvoiceFormProps {
  buyers: Counterparty[];
  buyersLoading: boolean;
  today: string;
  busy: boolean;
  problem: string | null;
  /** issue = save the draft and issue it straight away */
  onSubmit: (v: InvoiceValues, issue: boolean) => void;
  onCancel: () => void;
}

/**
 * New invoice. Credit terms default to 30 days; the due date is shown live (issue date + terms).
 * Totals are a PREVIEW — the server computes the invoice's real totals when it is saved.
 */
export function InvoiceForm({ buyers, buyersLoading, today, busy, problem, onSubmit, onCancel }: InvoiceFormProps) {
  const wide = useWidgetRuntime().context.tier !== 'xs';
  const [v, setV] = useState<InvoiceValues>(() => blankInvoice(today));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const set = <K extends keyof InvoiceValues>(k: K, val: InvoiceValues[K]) => setV((s) => ({ ...s, [k]: val }));
  const validateField = (path: string) => {
    const r = invoiceSchema.safeParse(v);
    const all = r.success ? {} : fieldErrors(r.error.issues);
    setErrors((e) => {
      const next = { ...e };
      if (all[path]) next[path] = all[path];
      else delete next[path];
      return next;
    });
  };
  const submit = (issue: boolean) => (e?: FormEvent) => {
    e?.preventDefault();
    const r = invoiceSchema.safeParse(v);
    if (!r.success) {
      setErrors(fieldErrors(r.error.issues));
      return;
    }
    setErrors({});
    onSubmit(r.data, issue);
  };
  const setLine = (i: number, next: LineValues) => setV((s) => ({ ...s, lines: s.lines.map((l, j) => (j === i ? next : l)) }));
  const lineErrors = (i: number) => ({
    description: errors[`lines.${i}.description`], quantity: errors[`lines.${i}.quantity`], unitPrice: errors[`lines.${i}.unitPrice`],
  });
  const totals = previewTotals(v.lines);
  const due = /^\d{4}-\d{2}-\d{2}$/.test(v.issueDate) ? dueDateFor(v.issueDate, v.creditDays) : null;
  const buyer = buyers.find((b) => b.tenantId === v.buyerTenantId);

  return (
    <form className="grid gap-3" onSubmit={submit(false)} noValidate aria-label="New invoice">
      <div className={wide ? 'grid grid-cols-2 gap-3' : 'grid gap-3'}>
        <SelectField label="Bill to" value={v.buyerTenantId} error={errors.buyerTenantId}
          placeholder={buyersLoading ? 'Loading your network…' : buyers.length ? 'Choose an organisation' : 'No organisations to invoice yet'}
          options={buyers.map((b) => ({ value: b.tenantId, label: `${b.name} · ${b.subType.toLowerCase()}` }))}
          onBlur={() => validateField('buyerTenantId')}
          onChange={(e) => {
            const next = buyers.find((b) => b.tenantId === e.target.value);
            setV((s) => ({ ...s, buyerTenantId: e.target.value, currency: next && (CURRENCIES as readonly string[]).includes(next.currency) ? (next.currency as InvoiceValues['currency']) : s.currency }));
          }} />
        <SelectField label="Currency" value={v.currency} options={CURRENCIES.map((c) => ({ value: c, label: c }))}
          onChange={(e) => set('currency', e.target.value === 'ZAR' ? 'ZAR' : 'NAD')} />
        <FormField label="Issue date" type="date" value={v.issueDate} error={errors.issueDate}
          onBlur={() => validateField('issueDate')} onChange={(e) => set('issueDate', e.target.value)} />
        <SelectField label="Credit terms" value={String(v.creditDays)}
          hint={due ? `Payment due ${due}` : undefined}
          options={CREDIT_TERMS.map((t) => ({ value: String(t.days), label: t.label }))}
          onChange={(e) => set('creditDays', Number(e.target.value))} />
        <FormField label="Your reference" optional value={v.reference} error={errors.reference} placeholder="PO number"
          onBlur={() => validateField('reference')} onChange={(e) => set('reference', e.target.value)} />
      </div>

      {v.lines.map((l, i) => (
        <InvoiceLineEditor key={i} index={i} line={l} currency={v.currency} errors={lineErrors(i)} removable={v.lines.length > 1}
          onChange={(n) => setLine(i, n)} onRemove={() => setV((s) => ({ ...s, lines: s.lines.filter((_, j) => j !== i) }))} />
      ))}
      <div>
        <Button type="button" variant="ghost" onClick={() => setV((s) => ({ ...s, lines: [...s.lines, blankLine()] }))}>Add line</Button>
      </div>
      {errors.lines && <Alert kind="bad">{errors.lines}</Alert>}

      <TextAreaField label="Notes to the buyer (optional)" rows={2} value={v.notes} error={errors.notes}
        onChange={(e) => set('notes', e.target.value)} />

      <dl className="grid gap-1 rounded-xl border border-line bg-raised p-3 text-[13px]" aria-label="Invoice total preview">
        <div className="flex justify-between"><dt className="text-dim">Subtotal</dt><dd><Amount value={totals.subtotalMinor} currency={v.currency} /></dd></div>
        <div className="flex justify-between"><dt className="text-dim">VAT</dt><dd><Amount value={totals.taxMinor} currency={v.currency} /></dd></div>
        <div className="flex justify-between font-bold"><dt>Total{buyer ? ` for ${buyer.name}` : ''}</dt><dd><Amount value={totals.totalMinor} currency={v.currency} /></dd></div>
        {due && <div className="text-dim text-[12px]">{v.creditDays === 0 ? 'Due on receipt' : `Net ${v.creditDays} — due ${due}`}</div>}
      </dl>

      {problem && <Alert kind="bad">{problem}</Alert>}
      <div className="flex flex-wrap justify-end gap-2">
        <Button type="button" variant="ghost" onClick={onCancel} disabled={busy}>Cancel</Button>
        <Button type="submit" variant="ghost" disabled={busy} data-action="invoice.create">{busy ? 'Saving…' : 'Save draft'}</Button>
        <Button type="button" variant="primary" disabled={busy} data-action="invoice.issue" onClick={() => submit(true)()}>
          {busy ? 'Saving…' : 'Save and issue'}
        </Button>
      </div>
    </form>
  );
}
