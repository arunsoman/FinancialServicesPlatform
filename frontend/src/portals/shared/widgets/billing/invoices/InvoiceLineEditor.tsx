import { clsx } from 'clsx';
import { Button } from '../../../ui/atoms';
import { useWidgetRuntime } from '../../core/runtime';
import { Amount } from '../../../ui/Amount';
import { FormField } from '../../kit/FormField';
import { SelectField } from '../../kit/SelectField';
import { TAX_RATES, lineAmounts, toMinor } from './model';
import type { LineValues } from './model';

export interface InvoiceLineEditorProps {
  index: number;
  line: LineValues;
  currency: string;
  errors: Partial<Record<keyof LineValues, string>>;
  removable: boolean;
  onChange: (next: LineValues) => void;
  onRemove: () => void;
}

/** One invoice line: item, quantity, unit price (major units typed, minor units sent), VAT. */
export function InvoiceLineEditor({ index, line, currency, errors, removable, onChange, onRemove }: InvoiceLineEditorProps) {
  // layout follows the widget's own width (tier), never the viewport
  const row = useWidgetRuntime().context.tier !== 'xs';
  const unit = toMinor(line.unitPrice);
  const qty = Number(line.quantity);
  const amounts = unit && Number.isInteger(qty) && qty > 0 ? lineAmounts(qty, unit, Number(line.taxRateBps)) : null;
  return (
    <fieldset className={clsx('grid gap-2 rounded-xl border border-line p-3', row && 'grid-cols-[3fr_1fr_1.4fr_1.2fr_auto] items-end')}>
      <legend className="fsp-eyebrow px-1">Line {index + 1}</legend>
      <FormField label="Item or service" value={line.description} error={errors.description} placeholder="Maize meal 12.5 kg"
        onChange={(e) => onChange({ ...line, description: e.target.value })} />
      <FormField label="Qty" inputMode="numeric" className="tabular-nums" value={line.quantity} error={errors.quantity}
        onChange={(e) => onChange({ ...line, quantity: e.target.value })} />
      <FormField label={`Unit price (${currency})`} inputMode="decimal" placeholder="189.50" className="tabular-nums"
        value={line.unitPrice} error={errors.unitPrice} onChange={(e) => onChange({ ...line, unitPrice: e.target.value })} />
      <SelectField label="VAT" value={line.taxRateBps} options={TAX_RATES.map((t) => ({ value: t.bps, label: t.label }))}
        onChange={(e) => onChange({ ...line, taxRateBps: e.target.value === '0' ? '0' : '1500' })} />
      <Button type="button" variant="ghost" disabled={!removable} onClick={onRemove} aria-label={`Remove line ${index + 1}`}>Remove</Button>
      <p className={clsx('text-dim text-[12px]', row && 'col-span-5')} aria-live="polite">
        {amounts ? <>Line total <Amount value={amounts.totalMinor} currency={currency} /> (VAT <Amount value={amounts.taxMinor} currency={currency} />)</> : 'Enter quantity and price to see the line total.'}
      </p>
    </fieldset>
  );
}
