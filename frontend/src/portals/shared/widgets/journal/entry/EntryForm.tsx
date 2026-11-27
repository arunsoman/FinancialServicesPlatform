import { useState } from 'react';
import type { FormEvent } from 'react';
import { Alert, Button } from '../../../ui/atoms';
import { FormField } from '../../kit/FormField';
import type { GlAccountRow } from '../../kit/books';
import { LineEditor } from './LineEditor';
import { entrySchema } from './model';
import type { EntryValues, LineValues } from './model';

export interface EntryFormProps {
  accounts: GlAccountRow[];
  currency: string;
  busy: boolean;
  problem: string | null;
  onSubmit: (v: EntryValues) => void;
  onCancel: () => void;
}

const blankLine = (direction: 'DEBIT' | 'CREDIT'): LineValues => ({ glAccountId: '', direction, amount: '', memo: '' });

/**
 * Maker form → a DRAFT entry. Balance is the SERVER's rule (JE_NOT_BALANCED is shown verbatim);
 * the client validates shape on submit (ui-std §8) and never sums to "verify" money.
 */
export function EntryForm({ accounts, currency, busy, problem, onSubmit, onCancel }: EntryFormProps) {
  const today = new Date().toISOString().slice(0, 10);
  const [v, setV] = useState<EntryValues>({ postingDate: today, description: '', reference: '', lines: [blankLine('DEBIT'), blankLine('CREDIT')] });
  const [errors, setErrors] = useState<Record<string, string>>({});

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const r = entrySchema.safeParse(v);
    if (!r.success) {
      const next: Record<string, string> = {};
      for (const i of r.error.issues) next[i.path.join('.')] ??= i.message;
      setErrors(next);
      return;
    }
    setErrors({});
    onSubmit(r.data);
  };
  const setLine = (i: number, next: LineValues) => setV((s) => ({ ...s, lines: s.lines.map((l, j) => (j === i ? next : l)) }));
  const lineError = (i: number) => ['glAccountId', 'direction', 'amount', 'memo'].map((k) => errors[`lines.${i}.${k}`]).find(Boolean);

  return (
    <form className="grid gap-3 rounded-xl border border-line-strong bg-raised p-3" onSubmit={submit} noValidate>
      <div className="grid gap-3 sm:grid-cols-3">
        <FormField label="Posting date" type="date" value={v.postingDate} error={errors.postingDate}
          onChange={(e) => setV((s) => ({ ...s, postingDate: e.target.value }))} />
        <FormField label="Description" value={v.description} error={errors.description}
          onChange={(e) => setV((s) => ({ ...s, description: e.target.value }))} />
        <FormField label="Reference" optional value={v.reference} error={errors.reference}
          onChange={(e) => setV((s) => ({ ...s, reference: e.target.value }))} />
      </div>
      {v.lines.map((l, i) => (
        <LineEditor key={i} index={i} line={l} accounts={accounts} currency={currency} error={lineError(i)}
          removable={v.lines.length > 2} onChange={(n) => setLine(i, n)}
          onRemove={() => setV((s) => ({ ...s, lines: s.lines.filter((_, j) => j !== i) }))} />
      ))}
      {errors.lines && <Alert kind="bad">{errors.lines}</Alert>}
      {problem && <Alert kind="bad">{problem}</Alert>}
      <div className="flex flex-wrap justify-between gap-2">
        <Button type="button" variant="ghost" onClick={() => setV((s) => ({ ...s, lines: [...s.lines, blankLine('DEBIT')] }))}>Add line</Button>
        <div className="flex gap-2">
          <Button type="button" variant="ghost" onClick={onCancel} disabled={busy}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={busy} data-action="je.create">{busy ? 'Saving…' : 'Save draft'}</Button>
        </div>
      </div>
    </form>
  );
}
