import { Button } from '../../../ui/atoms';
import { FormField } from '../../kit/FormField';
import { SelectField } from '../../kit/SelectField';
import type { GlAccountRow } from '../../kit/books';
import type { LineValues } from './model';

export interface LineEditorProps {
  index: number;
  line: LineValues;
  accounts: GlAccountRow[];
  currency: string;
  error?: string;
  removable: boolean;
  onChange: (next: LineValues) => void;
  onRemove: () => void;
}

/** One journal line: account, side, amount (major units typed, minor units sent), memo. */
export function LineEditor({ index, line, accounts, currency, error, removable, onChange, onRemove }: LineEditorProps) {
  return (
    <fieldset className="grid gap-2 rounded-xl border border-line p-3 sm:grid-cols-[2fr_1fr_1fr_2fr_auto] sm:items-end">
      <legend className="fsp-eyebrow px-1">Line {index + 1}</legend>
      <SelectField label="Account" value={line.glAccountId} placeholder="Choose an account"
        options={accounts.map((a) => ({ value: a.id, label: `${a.code} · ${a.name}` }))}
        onChange={(e) => onChange({ ...line, glAccountId: e.target.value })} />
      <SelectField label="Side" value={line.direction}
        options={[{ value: 'DEBIT', label: 'Debit' }, { value: 'CREDIT', label: 'Credit' }]}
        onChange={(e) => onChange({ ...line, direction: e.target.value === 'CREDIT' ? 'CREDIT' : 'DEBIT' })} />
      <FormField label={`Amount (${currency})`} inputMode="decimal" placeholder="2500.00" className="tabular-nums"
        value={line.amount} onChange={(e) => onChange({ ...line, amount: e.target.value })} />
      <FormField label="Memo" optional value={line.memo} onChange={(e) => onChange({ ...line, memo: e.target.value })} />
      <Button type="button" variant="ghost" disabled={!removable} onClick={onRemove} aria-label={`Remove line ${index + 1}`}>Remove</Button>
      {error && <p className="text-[12px] text-status-danger sm:col-span-5" role="alert">{error}</p>}
    </fieldset>
  );
}
