import { useState } from 'react';
import type { FormEvent } from 'react';
import { Button } from '../../../ui/atoms';
import { FormField } from '../../kit/FormField';
import { pspSchema } from './model';
import type { PspValues } from './model';

export interface PspFormProps {
  busy: boolean;
  problem: string | null;
  onSubmit: (v: PspValues) => void;
  onCancel: () => void;
}

type Errors = Partial<Record<keyof PspValues, string>>;
const EMPTY: PspValues = { legalName: '', adminUsername: '', adminFullName: '', checkerUsername: '', checkerFullName: '' };

/** New PSP: the organization is born with an administrator AND a distinct designated checker. */
export function PspForm({ busy, problem, onSubmit, onCancel }: PspFormProps) {
  const [values, setValues] = useState<PspValues>(EMPTY);
  const [errors, setErrors] = useState<Errors>({});
  const check = (only?: keyof PspValues): PspValues | null => {
    const r = pspSchema.safeParse(values);
    const next: Errors = {};
    if (!r.success) for (const i of r.error.issues) next[i.path[0] as keyof PspValues] ??= i.message;
    setErrors((e) => (only ? { ...e, [only]: next[only] } : next));
    return r.success ? r.data : null;
  };
  const field = (k: keyof PspValues, label: string, placeholder: string) => (
    <FormField label={label} placeholder={placeholder} value={values[k]} error={errors[k]}
      onChange={(e) => setValues((v) => ({ ...v, [k]: e.target.value }))} onBlur={() => check(k)} />
  );
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const ok = check();
    if (ok) onSubmit(ok);
  };
  return (
    <form className="grid gap-3 rounded-xl border border-line-strong bg-raised p-3 sm:grid-cols-2" onSubmit={submit} noValidate>
      <div className="sm:col-span-2">{field('legalName', 'Legal name', 'Acme Manufacturers (Pty) Ltd')}</div>
      {field('adminUsername', 'Administrator username', 'acme.admin')}
      {field('adminFullName', 'Administrator name', 'Ndapewa Shikongo')}
      {field('checkerUsername', 'Checker username', 'acme.checker')}
      {field('checkerFullName', 'Checker name', 'Tangeni Amupolo')}
      {problem && <p className="text-[12px] text-status-danger sm:col-span-2" role="alert">{problem}</p>}
      <div className="flex justify-end gap-2 sm:col-span-2">
        <Button type="button" variant="ghost" onClick={onCancel} disabled={busy}>Cancel</Button>
        <Button type="submit" variant="primary" disabled={busy} data-action="org.create">{busy ? 'Creating…' : 'Create organization'}</Button>
      </div>
    </form>
  );
}
