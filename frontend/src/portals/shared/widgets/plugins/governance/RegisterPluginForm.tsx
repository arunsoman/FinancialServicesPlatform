import { useState } from 'react';
import type { FormEvent } from 'react';
import { Button } from '../../../ui/atoms';
import { FormField } from '../../kit/FormField';
import { TextAreaField } from '../../kit/TextAreaField';
import { registerSchema, sha256Hex } from './model';
import type { RegisterValues } from './model';

export interface RegisterPluginFormProps {
  busy: boolean;
  onSubmit: (v: { code: string; version: string; baseUrl: string; manifest: string; manifestHash: string }) => void;
  onCancel: () => void;
}

type Errors = Partial<Record<keyof RegisterValues, string>>;

/** Register a plugin TYPE (four-eyes PLUGIN_REGISTER); the manifest hash is computed here and re-checked by core. */
export function RegisterPluginForm({ busy, onSubmit, onCancel }: RegisterPluginFormProps) {
  const [values, setValues] = useState<RegisterValues>({ baseUrl: '', manifest: '' });
  const [errors, setErrors] = useState<Errors>({});
  const check = (only?: keyof RegisterValues): RegisterValues | null => {
    const r = registerSchema.safeParse(values);
    const next: Errors = {};
    if (!r.success) for (const i of r.error.issues) next[i.path[0] as keyof RegisterValues] ??= i.message;
    setErrors((e) => (only ? { ...e, [only]: next[only] } : next));
    return r.success ? r.data : null;
  };
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const ok = check();
    if (!ok) return;
    const m = JSON.parse(ok.manifest) as { code: string; version: string };
    onSubmit({ code: m.code, version: m.version, baseUrl: ok.baseUrl, manifest: ok.manifest, manifestHash: await sha256Hex(ok.manifest) });
  };
  return (
    <form className="grid gap-3 rounded-xl border border-line-strong bg-raised p-3" onSubmit={(e) => void submit(e)} noValidate>
      <FormField label="Base URL" placeholder="http://localhost:8083" value={values.baseUrl} error={errors.baseUrl}
        onChange={(e) => setValues((v) => ({ ...v, baseUrl: e.target.value }))} onBlur={() => check('baseUrl')} />
      <TextAreaField label="Manifest (JSON)" rows={6} value={values.manifest} error={errors.manifest}
        hint="The plugin-manifest.json shipped with the plugin. Its SHA-256 is recorded with the registration."
        onChange={(e) => setValues((v) => ({ ...v, manifest: e.target.value }))} onBlur={() => check('manifest')} />
      <div className="flex justify-end gap-2">
        <Button type="button" variant="ghost" onClick={onCancel} disabled={busy}>Cancel</Button>
        <Button type="submit" variant="primary" disabled={busy} data-action="plugin.register">{busy ? 'Sending…' : 'Request registration'}</Button>
      </div>
    </form>
  );
}
