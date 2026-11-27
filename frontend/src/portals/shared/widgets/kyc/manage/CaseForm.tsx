import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { clsx } from 'clsx';
import { Alert, Button } from '../../../ui/atoms';
import { FormField } from '../../kit/FormField';
import { SelectField } from '../../kit/SelectField';
import {
  emptyBank, emptyUbo, FIELD_FOR_CODE, MASK, optionsOf, STEPS, stepOf, subTypeOptions, validateStep,
} from './model';
import type { BankValues, CaseFormValues, FieldErrors, MasterValue, Problem, StepId, UboValues } from './model';
import { ContactFields } from '../../kit/ContactFields';
import type { ContactValues } from '../../kit/contact';

export interface CaseFormProps {
  mode: 'create' | 'edit';
  initial: CaseFormValues;
  master: MasterValue[] | undefined;
  masterLoading: boolean;
  busy: boolean;
  problem: Problem | null;
  onSubmit: (values: CaseFormValues) => void;
  onCancel: () => void;
  onDirtyChange: (dirty: boolean) => void;
  /** the only organization type allowed here (core's ladder: the parent's next level) */
  lockedSubType?: CaseFormValues['subType'];
  /** why it is fixed, e.g. "Below Acme you can onboard a distributor only." */
  lockedSubTypeHint?: string;
}



function Section({ title, children, action }: { title: string; children: ReactNode; action?: ReactNode }) {
  return (
    <section className="grid gap-3">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-dim text-[10.5px] font-bold uppercase tracking-[.14em]">{title}</h3>
        {action}
      </div>
      {children}
    </section>
  );
}

/**
 * Add / edit a KYC application in five steps (Company · Business · Addresses · Owners · Bank
 * accounts). Each step validates on blur and on Next (zod, NamibiaRules mirrors); the server's
 * field problems are routed back to their field and step. Masked numbers stay masked and are
 * sent as "unchanged".
 */
export function CaseForm({
  mode, initial, master, masterLoading, busy, problem, onSubmit, onCancel, onDirtyChange, lockedSubType, lockedSubTypeHint,
}: CaseFormProps) {
  const [v, setV] = useState<CaseFormValues>(initial);
  const [step, setStep] = useState<StepId>('company');
  const [touched, setTouched] = useState<Set<string>>(() => new Set());
  const [attempted, setAttempted] = useState<Set<StepId>>(() => new Set());
  const [server, setServer] = useState<FieldErrors>({});
  const bodyRef = useRef<HTMLDivElement>(null);

  const dirty = useMemo(() => JSON.stringify(v) !== JSON.stringify(initial), [v, initial]);
  useEffect(() => onDirtyChange(dirty), [dirty, onDirtyChange]);

  // server field problem → its field + step (409 REGISTRATION_TAKEN, 400 UBO_THRESHOLD…)
  useEffect(() => {
    if (!problem) return;
    const field = FIELD_FOR_CODE[problem.code];
    if (field) {
      setServer({ [field]: problem.message });
      setStep(stepOf(field));
    }
  }, [problem]);

  useEffect(() => {
    bodyRef.current?.scrollTo({ top: 0 });
  }, [step]);

  const stepErrors = validateStep(step, v);
  const err = (path: string): string | undefined =>
    server[path] ?? (touched.has(path) || attempted.has(step) ? stepErrors[path] : undefined);

  const set = <K extends keyof CaseFormValues>(k: K, value: CaseFormValues[K]) => {
    setV((p) => ({ ...p, [k]: value }));
    if (server[k as string]) setServer({});
  };
  const touch = (path: string) => setTouched((t) => (t.has(path) ? t : new Set(t).add(path)));

  const text = (k: keyof CaseFormValues, label: string, extra: Partial<Parameters<typeof FormField>[0]> = {}) => (
    <FormField
      label={label}
      value={String(v[k] ?? '')}
      onChange={(e) => set(k, e.target.value as never)}
      onBlur={() => touch(k)}
      error={err(k)}
      disabled={busy}
      {...extra}
    />
  );
  const select = (k: keyof CaseFormValues, label: string, options: { value: string; label: string }[], hint?: string) => (
    <SelectField
      label={label}
      value={String(v[k] ?? '')}
      options={options}
      placeholder={masterLoading ? 'Loading…' : 'Choose…'}
      onChange={(e) => set(k, e.target.value as never)}
      onBlur={() => touch(k)}
      error={err(k)}
      hint={hint}
      disabled={busy}
    />
  );

  // ---- list rows (owners, bank accounts)
  const setUbo = (i: number, patch: Partial<UboValues>) => set('ubos', v.ubos.map((u, n) => (n === i ? { ...u, ...patch } : u)));
  const setBank = (i: number, patch: Partial<BankValues>) =>
    set('bankAccounts', v.bankAccounts.map((b, n) => (n === i ? { ...b, ...patch } : b)));
  const rowText = (path: string, label: string, value: string, onChange: (s: string) => void, extra: Partial<Parameters<typeof FormField>[0]> = {}) => (
    <FormField label={label} value={value} onChange={(e) => onChange(e.target.value)} onBlur={() => touch(path)} error={err(path)} disabled={busy} {...extra} />
  );
  const rowSelect = (path: string, label: string, value: string, options: { value: string; label: string }[], onChange: (s: string) => void) => (
    <SelectField label={label} value={value} options={options} placeholder={masterLoading ? 'Loading…' : 'Choose…'} onChange={(e) => onChange(e.target.value)} onBlur={() => touch(path)} error={err(path)} disabled={busy} />
  );
  const maskedHint = (value: string) => (value.startsWith(MASK) ? 'Stored number is masked — leave it to keep it, or type a new one.' : undefined);

  // ---- org people (create only): each activates by link/code on their primary contact
  const setPerson = (who: 'admin' | 'checker', patch: Partial<ContactValues>) => {
    setV((p) => ({ ...p, [who]: { ...p[who], ...patch } }));
    if (Object.keys(server).some((k) => k.startsWith(who))) setServer({});
  };

  // ---- navigation (editing never changes the org's people — no Sign-in step)
  const steps = mode === 'create' ? STEPS : STEPS.filter((s) => s.id !== 'access');
  const idx = steps.findIndex((s) => s.id === step);
  const last = idx === steps.length - 1;
  const firstInvalid = (): StepId | null => steps.find((s) => Object.keys(validateStep(s.id, v)).length > 0)?.id ?? null;

  const next = () => {
    setAttempted((a) => new Set(a).add(step));
    if (Object.keys(stepErrors).length) return;
    if (!last) {
      setStep(steps[idx + 1].id);
      return;
    }
    const bad = firstInvalid();
    if (bad) {
      setAttempted(new Set(steps.map((s) => s.id)));
      setStep(bad);
      return;
    }
    onSubmit(v);
  };

  const formProblem = problem && !FIELD_FOR_CODE[problem.code] ? problem : null;

  return (
    <form
      className="flex h-full min-h-0 flex-col"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        next();
      }}
    >
      <ol className="flex gap-1 overflow-x-auto border-b border-line px-5 py-3" aria-label="Steps">
        {steps.map((s, i) => {
          const done = i < idx || (attempted.has(s.id) && Object.keys(validateStep(s.id, v)).length === 0);
          const bad = attempted.has(s.id) && Object.keys(validateStep(s.id, v)).length > 0;
          return (
            <li key={s.id} className="shrink-0">
              <button
                type="button"
                aria-current={s.id === step ? 'step' : undefined}
                onClick={() => (i <= idx || attempted.has(s.id) ? setStep(s.id) : next())}
                className={clsx(
                  'flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[12px] font-semibold',
                  s.id === step ? 'border-transparent bg-accent text-invert' : 'border-line text-dim hover:border-line-strong',
                )}
              >
                <span
                  aria-hidden="true"
                  className={clsx(
                    'grid h-4 w-4 place-items-center rounded-full text-[10px]',
                    bad ? 'bg-status-danger text-invert' : done && s.id !== step ? 'bg-status-success text-invert' : 'border border-current',
                  )}
                >
                  {bad ? '!' : done && s.id !== step ? '✓' : i + 1}
                </span>
                {s.label}
                {bad && <span className="sr-only"> (has errors)</span>}
              </button>
            </li>
          );
        })}
      </ol>

      <div ref={bodyRef} className="grid min-h-0 flex-1 content-start gap-5 overflow-y-auto p-5">
        {formProblem && <Alert kind="bad">{formProblem.message}</Alert>}

        {step === 'company' && (
          <Section title="Company">
            <div className="grid gap-3 sm:grid-cols-2">
              <SelectField
                label="Organization type"
                value={v.subType}
                options={subTypeOptions(mode, lockedSubType, v.subType)}
                placeholder="Choose…"
                onChange={(e) => set('subType', e.target.value as CaseFormValues['subType'])}
                onBlur={() => touch('subType')}
                error={err('subType')}
                disabled={busy || mode === 'edit' || Boolean(lockedSubType)}
                hint={mode === 'edit' ? 'Fixed once the organization exists.' : lockedSubType ? lockedSubTypeHint : undefined}
              />
              {select('entityType', 'Entity type', optionsOf(master, 'entityType'))}
              {text('legalName', 'Registered name', { autoComplete: 'organization', className: 'sm:col-span-2' })}
              {text('dbaName', 'Trading as', { optional: true, className: 'sm:col-span-2' })}
              {text('registrationNumber', 'Business Registration no.', { hint: 'e.g. CC/2019/04512', autoCapitalize: 'characters' })}
              {text('taxId', 'Tax ID (TIN)', { inputMode: 'numeric', maxLength: 8, hint: '8 digits' })}
              {text('incorporationDate', 'Incorporation date', { type: 'date' })}
            </div>
          </Section>
        )}

        {step === 'business' && (
          <Section title="Business">
            <div className="grid gap-3 sm:grid-cols-2">
              {select('mccCode', 'Merchant category', optionsOf(master, 'mcc'))}
              {text('websiteUrl', 'Website', { optional: true, type: 'url', placeholder: 'https://' })}
              {text('expectedMonthlyVolume', 'Expected monthly volume (NAD)', { inputMode: 'decimal', className: 'tabular-nums' })}
              {text('avgTicketSize', 'Average ticket (NAD)', { inputMode: 'decimal', className: 'tabular-nums' })}
            </div>
          </Section>
        )}

        {step === 'addresses' && (
          <>
            <Section title="Registered address">
              <div className="grid gap-3 sm:grid-cols-2">
                {text('registeredAddress', 'Street address', { autoComplete: 'street-address', className: 'sm:col-span-2' })}
                {text('registeredCity', 'Town / city', { autoComplete: 'address-level2' })}
                {select('registeredRegion', 'Region', optionsOf(master, 'region'))}
                {text('registeredPostal', 'Postal code', { optional: true, autoComplete: 'postal-code' })}
              </div>
            </Section>
            <Section title="Operating address">
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={v.operationalAddressSame}
                  onChange={(e) => set('operationalAddressSame', e.target.checked)}
                  disabled={busy}
                />
                Same as the registered address
              </label>
              {!v.operationalAddressSame && (
                <div className="grid gap-3 sm:grid-cols-2">
                  {text('operationalAddress', 'Street address', { className: 'sm:col-span-2' })}
                  {text('operationalCity', 'Town / city')}
                  {select('operationalRegion', 'Region', optionsOf(master, 'region'))}
                  {text('operationalPostal', 'Postal code', { optional: true })}
                </div>
              )}
            </Section>
          </>
        )}

        {step === 'owners' && (
          <Section
            title="Beneficial owners (25% or more)"
            action={
              <Button type="button" variant="ghost" disabled={busy || v.ubos.length >= 4} onClick={() => set('ubos', [...v.ubos, emptyUbo()])}>
                + Add owner
              </Button>
            }
          >
            {err('ubos') && <Alert kind="bad">{err('ubos')}</Alert>}
            {v.ubos.map((u, i) => (
              <fieldset key={u.id} className="grid gap-3 rounded-xl border border-line p-3 sm:grid-cols-2">
                <legend className="px-1 text-[12px] font-semibold">Owner {i + 1}</legend>
                {rowText(`ubos.${i}.fullName`, 'Full name', u.fullName, (s) => setUbo(i, { fullName: s }), { className: 'sm:col-span-2' })}
                {rowSelect(`ubos.${i}.idDocType`, 'ID document', u.idDocType, optionsOf(master, 'idDocType'), (s) => setUbo(i, { idDocType: s }))}
                {rowText(`ubos.${i}.idNumber`, 'ID number', u.idNumber, (s) => setUbo(i, { idNumber: s }), { hint: maskedHint(u.idNumber), autoComplete: 'off' })}
                {rowText(`ubos.${i}.dob`, 'Date of birth', u.dob, (s) => setUbo(i, { dob: s }), { type: 'date' })}
                {rowSelect(`ubos.${i}.nationality`, 'Nationality', u.nationality, optionsOf(master, 'nationality'), (s) => setUbo(i, { nationality: s }))}
                {rowText(`ubos.${i}.ownershipPct`, 'Ownership %', u.ownershipPct, (s) => setUbo(i, { ownershipPct: s }), { inputMode: 'decimal' })}
                <div className="flex items-end justify-between gap-2">
                  <label className="flex items-center gap-2 pb-2 text-sm">
                    <input type="checkbox" checked={u.isPep} onChange={(e) => setUbo(i, { isPep: e.target.checked })} disabled={busy} />
                    Politically exposed person
                  </label>
                  {v.ubos.length > 1 && (
                    <Button type="button" variant="ghost" disabled={busy} onClick={() => set('ubos', v.ubos.filter((_, n) => n !== i))}>
                      Remove
                    </Button>
                  )}
                </div>
              </fieldset>
            ))}
          </Section>
        )}

        {step === 'banking' && (
          <>
            <Section
              title="Settlement bank accounts"
              action={
                <Button type="button" variant="ghost" disabled={busy || v.bankAccounts.length >= 3} onClick={() => set('bankAccounts', [...v.bankAccounts, emptyBank()])}>
                  + Add account
                </Button>
              }
            >
              {err('bankAccounts') && <Alert kind="bad">{err('bankAccounts')}</Alert>}
              {v.bankAccounts.map((b, i) => (
                <fieldset key={b.id} className="grid gap-3 rounded-xl border border-line p-3 sm:grid-cols-2">
                  <legend className="px-1 text-[12px] font-semibold">Account {i + 1}</legend>
                  {rowText(`bankAccounts.${i}.holderName`, 'Account holder', b.holderName, (s) => setBank(i, { holderName: s }), { className: 'sm:col-span-2' })}
                  {rowSelect(`bankAccounts.${i}.bankName`, 'Bank', b.bankName, optionsOf(master, 'bank'), (s) => setBank(i, { bankName: s }))}
                  {rowText(`bankAccounts.${i}.branchCode`, 'Branch code', b.branchCode, (s) => setBank(i, { branchCode: s }), { inputMode: 'numeric', maxLength: 6 })}
                  {rowText(`bankAccounts.${i}.accountNumber`, 'Account number', b.accountNumber, (s) => setBank(i, { accountNumber: s }), { inputMode: 'numeric', hint: maskedHint(b.accountNumber), autoComplete: 'off' })}
                  {rowSelect(`bankAccounts.${i}.accountType`, 'Account type', b.accountType, optionsOf(master, 'accountType'), (s) => setBank(i, { accountType: s }))}
                  {v.bankAccounts.length > 1 && (
                    <div className="flex justify-end sm:col-span-2">
                      <Button type="button" variant="ghost" disabled={busy} onClick={() => set('bankAccounts', v.bankAccounts.filter((_, n) => n !== i))}>
                        Remove
                      </Button>
                    </div>
                  )}
                </fieldset>
              ))}
            </Section>
          </>
        )}

        {step === 'access' && (
          <>
            {(['admin', 'checker'] as const).map((who) => (
              <Section key={who} title={who === 'admin' ? 'Administrator' : 'Checker (approves the administrator’s requests)'}>
                <div className="grid gap-3">
                  <FormField
                    label="Full name"
                    value={v[who].fullName}
                    onChange={(e) => setPerson(who, { fullName: e.target.value })}
                    onBlur={() => touch(`${who}.fullName`)}
                    error={err(`${who}.fullName`)}
                    autoComplete="off"
                    disabled={busy}
                  />
                  <ContactFields
                    path={who}
                    value={v[who]}
                    onChange={(patch) => setPerson(who, patch)}
                    err={err}
                    touch={touch}
                    disabled={busy}
                  />
                </div>
              </Section>
            ))}
            <Alert kind="info">
              Submitting creates the organization (inactive), screens it and opens a review. The administrator and the checker each
              get a one-time activation and choose their own password — they can sign in only after that. Someone else must approve
              the application — you can’t decide an application you submitted.
            </Alert>
          </>
        )}
      </div>

      <div className="flex flex-wrap items-center gap-2 border-t border-line p-4">
        <Button type="button" variant="ghost" onClick={onCancel} disabled={busy}>Cancel</Button>
        <span className="text-dim text-[12px]">Step {idx + 1} of {steps.length}</span>
        <span className="flex-1" />
        {idx > 0 && (
          <Button type="button" variant="ghost" onClick={() => setStep(steps[idx - 1].id)} disabled={busy}>‹ Back</Button>
        )}
        <Button type="submit" variant="primary" disabled={busy} aria-busy={busy} data-action={last ? (mode === 'create' ? 'kyc.case.create' : 'kyc.case.update') : undefined}>
          {busy ? 'Saving…' : !last ? 'Next ›' : mode === 'create' ? 'Submit application' : 'Save changes'}
        </Button>
      </div>
    </form>
  );
}
