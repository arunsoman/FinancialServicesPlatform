/**
 * Customer Registration — step 1 of 2 (staff, the maker). Personal details, address (the
 * organization application's registered/operating pair, as residential/postal) + contact.
 * There is deliberately NO password field: the customer chooses it themselves from the
 * activation sent to the primary contact (FAPI 2.0 — credentials never pass through staff,
 * the BFF or this SPA). Step 2 is the checker activating the account once they have.
 */
import { useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Alert, Button } from '../../../ui/atoms';
import { FormField } from '../../kit/FormField';
import { SelectField } from '../../kit/SelectField';
import { ContactFields } from '../../kit/ContactFields';
import { optionsOf } from '../manage/model';
import type { MasterValue, Problem } from '../manage/model';
import { FIELD_FOR_CODE, validateCustomer } from './model';
import type { AddressValues, CustomerFormValues, FieldErrors } from './model';

export interface CustomerFormProps {
  initial: CustomerFormValues;
  master: MasterValue[] | undefined;
  masterLoading: boolean;
  busy: boolean;
  problem: Problem | null;
  onSubmit: (values: CustomerFormValues) => void;
  onCancel: () => void;
  onDirtyChange: (dirty: boolean) => void;
}

function Section({ icon, title, children }: { icon: string; title: string; children: ReactNode }) {
  return (
    <section className="grid gap-3">
      <h3 className="flex items-center gap-2 text-[11px] font-bold uppercase tracking-[.14em]">
        <span aria-hidden="true" className="text-dim">{icon}</span>
        {title}
      </h3>
      {children}
    </section>
  );
}

const FALLBACK_GENDERS = [
  { value: 'FEMALE', label: 'Female' }, { value: 'MALE', label: 'Male' },
  { value: 'OTHER', label: 'Other' }, { value: 'UNDISCLOSED', label: 'Prefer not to say' },
];

function AddressBlock({ path, value, regions, regionsLoading, err, touch, onChange, disabled }: {
  path: 'residentialAddress' | 'postalAddress';
  value: AddressValues;
  regions: { value: string; label: string }[];
  regionsLoading: boolean;
  err: (path: string) => string | undefined;
  touch: (path: string) => void;
  onChange: (patch: Partial<AddressValues>) => void;
  disabled: boolean;
}) {
  const home = path === 'residentialAddress';
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <FormField
        className="sm:col-span-2"
        label="Street address"
        placeholder={home ? 'e.g. 12 Robert Mugabe Ave' : 'e.g. PO Box 1234'}
        autoComplete={home ? 'street-address' : 'off'}
        value={value.line}
        onChange={(e) => onChange({ line: e.target.value })}
        onBlur={() => touch(`${path}.line`)}
        error={err(`${path}.line`)}
        disabled={disabled}
      />
      <FormField
        label="Town / city"
        autoComplete={home ? 'address-level2' : 'off'}
        value={value.city}
        onChange={(e) => onChange({ city: e.target.value })}
        onBlur={() => touch(`${path}.city`)}
        error={err(`${path}.city`)}
        disabled={disabled}
      />
      <SelectField
        label="Region"
        value={value.region}
        options={regions}
        placeholder={regionsLoading ? 'Loading…' : 'Select region'}
        onChange={(e) => onChange({ region: e.target.value })}
        onBlur={() => touch(`${path}.region`)}
        error={err(`${path}.region`)}
        disabled={disabled}
      />
      <FormField
        label="Postal code (optional)"
        autoComplete={home ? 'postal-code' : 'off'}
        value={value.postalCode}
        onChange={(e) => onChange({ postalCode: e.target.value })}
        onBlur={() => touch(`${path}.postalCode`)}
        error={err(`${path}.postalCode`)}
        disabled={disabled}
      />
    </div>
  );
}

export function CustomerForm({ initial, master, masterLoading, busy, problem, onSubmit, onCancel, onDirtyChange }: CustomerFormProps) {
  const [v, setV] = useState<CustomerFormValues>(initial);
  const [touched, setTouched] = useState<Set<string>>(() => new Set());
  const [attempted, setAttempted] = useState(false);
  const [server, setServer] = useState<FieldErrors>({});

  const dirty = useMemo(() => JSON.stringify(v) !== JSON.stringify(initial), [v, initial]);
  useEffect(() => onDirtyChange(dirty), [dirty, onDirtyChange]);

  // 409 CUSTOMER_EMAIL_TAKEN → "Email is already registered." next to the email field
  useEffect(() => {
    if (!problem) return;
    const field = FIELD_FOR_CODE[problem.code];
    if (field) setServer({ [field]: problem.message });
  }, [problem]);

  const errors = validateCustomer(v);
  const err = (path: string): string | undefined =>
    server[path] ?? (touched.has(path) || attempted ? errors[path] : undefined);
  const touch = (path: string) => setTouched((t) => (t.has(path) ? t : new Set(t).add(path)));
  const set = (patch: Partial<CustomerFormValues>) => {
    setV((p) => ({ ...p, ...patch }));
    const keys = Object.keys(patch);
    if (keys.some((k) => server[k])) setServer({});
  };

  const genders = optionsOf(master, 'gender');
  const regions = optionsOf(master, 'region');
  const formProblem = problem && !FIELD_FOR_CODE[problem.code] ? problem : null;

  return (
    <form
      className="flex h-full min-h-0 flex-col"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        setAttempted(true);
        if (Object.keys(errors).length === 0) onSubmit(v);
      }}
    >
      <div className="flex items-start justify-between gap-3 border-b border-line px-5 py-4">
        <div>
          <h2 className="text-base font-extrabold">Customer Registration</h2>
          <p className="text-dim text-[12.5px]">Create a new customer profile. The customer activates it themselves.</p>
        </div>
        <span className="shrink-0 rounded-lg border border-line px-2.5 py-1 text-[11px] font-bold uppercase tracking-[.08em]">
          Step 1 of 2
        </span>
      </div>

      <div className="grid min-h-0 flex-1 content-start gap-6 overflow-y-auto p-5">
        {formProblem && <Alert kind="bad">{formProblem.message}</Alert>}

        <Section icon="👤" title="Personal details">
          <div className="grid gap-3 sm:grid-cols-2">
            <FormField
              label="First name"
              placeholder="e.g. John"
              autoComplete="off"
              value={v.firstName}
              onChange={(e) => set({ firstName: e.target.value })}
              onBlur={() => touch('firstName')}
              error={err('firstName')}
              disabled={busy}
            />
            <FormField
              label="Last name"
              placeholder="e.g. Doe"
              autoComplete="off"
              value={v.lastName}
              onChange={(e) => set({ lastName: e.target.value })}
              onBlur={() => touch('lastName')}
              error={err('lastName')}
              disabled={busy}
            />
            <SelectField
              label="Gender"
              value={v.gender}
              options={genders.length ? genders : FALLBACK_GENDERS}
              placeholder={masterLoading ? 'Loading…' : 'Select gender'}
              onChange={(e) => set({ gender: e.target.value })}
              onBlur={() => touch('gender')}
              error={err('gender')}
              disabled={busy}
            />
            <FormField
              label="Date of birth"
              type="date"
              max={new Date().toISOString().slice(0, 10)}
              value={v.dateOfBirth}
              onChange={(e) => set({ dateOfBirth: e.target.value })}
              onBlur={() => touch('dateOfBirth')}
              error={err('dateOfBirth')}
              disabled={busy}
            />
          </div>
        </Section>

        <hr className="border-line" />

        <Section icon="⌂" title="Address">
          <h4 className="text-dim text-[11.5px] font-semibold">Residential address</h4>
          <AddressBlock
            path="residentialAddress"
            value={v.residentialAddress}
            regions={regions}
            regionsLoading={masterLoading}
            err={err}
            touch={touch}
            onChange={(p) => set({ residentialAddress: { ...v.residentialAddress, ...p } })}
            disabled={busy}
          />
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={v.postalSameAsResidential}
              onChange={(e) => set({ postalSameAsResidential: e.target.checked })}
              disabled={busy}
            />
            Postal address is the same as the residential address
          </label>
          {!v.postalSameAsResidential && (
            <>
              <h4 className="text-dim text-[11.5px] font-semibold">Postal address</h4>
              <AddressBlock
                path="postalAddress"
                value={v.postalAddress}
                regions={regions}
                regionsLoading={masterLoading}
                err={err}
                touch={touch}
                onChange={(p) => set({ postalAddress: { ...v.postalAddress, ...p } })}
                disabled={busy}
              />
            </>
          )}
        </Section>

        <hr className="border-line" />

        <Section icon="✉" title="Contact & security">
          <ContactFields
            path=""
            value={v}
            onChange={(patch) => set(patch as Partial<CustomerFormValues>)}
            err={err}
            touch={touch}
            disabled={busy}
          />
          <Alert kind="info">
            <strong className="block">No password is set here.</strong>
            The customer receives a one-time {v.primaryContact === 'PHONE' ? 'code by SMS' : 'link by email'} and chooses their own
            password at the FSP Identity Provider. Step 2: once they have, another reviewer activates the account — only then can
            they sign in. You can’t activate a customer you registered.
          </Alert>
        </Section>
      </div>

      <div className="flex flex-wrap items-center gap-2 border-t border-line p-4">
        <Button type="button" variant="ghost" onClick={onCancel} disabled={busy}>Cancel</Button>
        <span className="flex-1" />
        <Button type="submit" variant="primary" disabled={busy} aria-busy={busy} data-action="kyc.customers.create">
          {busy ? 'Registering…' : 'Register customer'}
        </Button>
      </div>
    </form>
  );
}
