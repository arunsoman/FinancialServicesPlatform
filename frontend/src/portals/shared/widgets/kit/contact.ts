/**
 * Contact + primary-contact rules shared by the organization application (administrator /
 * checker) and the customer registration. Mirrors the server (core PersonProvisioning.contact,
 * plugin KycCustomerService.validate) so mistakes show on blur; the server still decides.
 *
 * The primary contact is where the person's ACTIVATION goes — a one-time link by email or a
 * one-time code by SMS. Nobody sets a password for them: they choose it themselves at the
 * Identity Provider, and only then can they sign in.
 */
import { z } from 'zod';

export const PRIMARY_CONTACTS = ['EMAIL', 'PHONE'] as const;
export type PrimaryContact = (typeof PRIMARY_CONTACTS)[number];

export interface ContactValues {
  fullName: string;
  email: string;
  phone: string;
  primaryContact: PrimaryContact;
}

export const emptyContact = (): ContactValues => ({ fullName: '', email: '', phone: '', primaryContact: 'EMAIL' });

const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

/** "+264 81-123 4567" / "00264…" → "+264811234567"; null when not international format. */
export function toE164(raw: string): string | null {
  let c = raw.replace(/[\s()\-.]/g, '');
  if (c.startsWith('00')) c = `+${c.slice(2)}`;
  return /^\+[1-9]\d{7,14}$/.test(c) ? c : null;
}

/** Email + phone + which one is primary (the chosen one is required, the other optional). */
export const contactRefine = (v: { email: string; phone: string; primaryContact: PrimaryContact }, ctx: z.RefinementCtx, prefix: (string | number)[] = []) => {
  const email = v.email.trim();
  const phone = v.phone.trim();
  if (email && !EMAIL_RE.test(email)) ctx.addIssue({ code: 'custom', path: [...prefix, 'email'], message: 'Enter a valid email address' });
  if (phone && !toE164(phone)) ctx.addIssue({ code: 'custom', path: [...prefix, 'phone'], message: 'Use international format, e.g. +264 81 123 4567' });
  if (v.primaryContact === 'EMAIL' && !email) ctx.addIssue({ code: 'custom', path: [...prefix, 'email'], message: 'Email is required — the activation link goes there' });
  if (v.primaryContact === 'PHONE' && !phone) ctx.addIssue({ code: 'custom', path: [...prefix, 'phone'], message: 'Phone is required — the activation code goes there by SMS' });
};

export const contactSchema = z
  .object({
    fullName: z.string().trim().min(2, 'Enter the full name').max(120),
    email: z.string(),
    phone: z.string(),
    primaryContact: z.enum(PRIMARY_CONTACTS),
  })
  .superRefine((v, ctx) => contactRefine(v, ctx));

export function contactToWire(c: ContactValues) {
  return {
    fullName: c.fullName.trim(),
    email: c.email.trim().toLowerCase() || null,
    phone: c.phone.trim() ? toE164(c.phone.trim()) ?? c.phone.trim() : null,
    primaryContact: c.primaryContact,
  };
}

export const channelLabel = (p: PrimaryContact | string | null | undefined) =>
  p === 'PHONE' ? 'SMS code to the phone' : 'link to the email';
