/**
 * Email + phone + "send the activation to" — one block used by user management, the organization application
 * (administrator, checker) and the customer registration. The chosen primary contact gets the
 * activation: a one-time link by email, or a one-time code by SMS.
 */
import { clsx } from 'clsx';
import { FormField } from './FormField';
import { PRIMARY_CONTACTS } from './contact';
import type { ContactValues, PrimaryContact } from './contact';

export interface ContactFieldsProps {
  /** field-path prefix for errors/touch, e.g. "admin" → "admin.email"; '' for top-level */
  path: string;
  value: Pick<ContactValues, 'email' | 'phone' | 'primaryContact'>;
  onChange: (patch: Partial<ContactValues>) => void;
  err: (path: string) => string | undefined;
  touch: (path: string) => void;
  disabled: boolean;
  /** server-known duplicates, e.g. "Email is already registered." */
  emailNote?: string;
}

const LABEL: Record<PrimaryContact, string> = { EMAIL: 'Email (link)', PHONE: 'Phone (SMS code)' };

export function ContactFields({ path, value, onChange, err, touch, disabled, emailNote }: ContactFieldsProps) {
  const p = (k: string) => (path ? `${path}.${k}` : k);
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <FormField
        label="Phone number"
        type="tel"
        autoComplete="off"
        inputMode="tel"
        placeholder="e.g. +264 81 123 4567"
        value={value.phone}
        onChange={(e) => onChange({ phone: e.target.value })}
        onBlur={() => touch(p('phone'))}
        error={err(p('phone'))}
        optional={value.primaryContact !== 'PHONE'}
        disabled={disabled}
      />
      <FormField
        label="Email address"
        type="email"
        autoComplete="off"
        placeholder="e.g. name@example.com"
        value={value.email}
        onChange={(e) => onChange({ email: e.target.value })}
        onBlur={() => touch(p('email'))}
        error={err(p('email')) ?? emailNote}
        optional={value.primaryContact !== 'EMAIL'}
        disabled={disabled}
      />
      <fieldset className="sm:col-span-2">
        <legend className="text-dim mb-1.5 text-[12px] font-semibold">Send the activation to</legend>
        <div className="flex flex-wrap gap-2" role="radiogroup">
          {PRIMARY_CONTACTS.map((c) => (
            <label
              key={c}
              className={clsx(
                'flex cursor-pointer items-center gap-2 rounded-full border px-3 py-1.5 text-[12.5px] font-semibold',
                value.primaryContact === c ? 'border-transparent bg-accent text-invert' : 'border-line text-dim hover:border-line-strong',
                disabled && 'cursor-not-allowed opacity-60',
              )}
            >
              <input
                type="radio"
                className="sr-only"
                name={p('primaryContact')}
                value={c}
                checked={value.primaryContact === c}
                onChange={() => onChange({ primaryContact: c })}
                disabled={disabled}
              />
              {LABEL[c]}
            </label>
          ))}
        </div>
        <p className="text-dim mt-1.5 text-[11.5px]">
          {value.primaryContact === 'PHONE'
            ? 'A one-time code is sent by SMS. They enter it with their phone number on the activation page and choose their own password.'
            : 'A one-time link is emailed. They open it and choose their own password — nobody else ever knows it.'}
        </p>
      </fieldset>
    </div>
  );
}
