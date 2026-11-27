/**
 * kyc.customers — customer KYC, served and owned by the KYC plugin (backend/plugins/kyc,
 * /v1/plugins/kyc via gateway; browser → BFF /api/plugins/kyc):
 *   1. Customer Registration (maker, org.onboard_child) — no password is ever entered by staff
 *   2. the customer activates from the link (email) / code (SMS) sent to their primary
 *      contact and sets their own password at the IdP
 *   3. a checker (≠ maker, kyc.approve) activates the account; kyc.reject rejects it
 * Core decides every call (PDP); the widget only picks affordances.
 */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { KycCustomers } from './widget';

const kycCustomersWidget = defineWidget({
  id: 'kyc.customers',
  version: '1.0.0',
  label: 'Customers',
  description: 'Customer KYC: register a customer, track their activation, and activate or reject them (four-eyes).',
  category: 'Onboarding',
  contexts: ['ANY'],
  requires: ['kyc.review'],
  sources: ['kyc.customers.page', 'kyc.customers.view', 'kyc.master.list'],
  commands: ['kyc.customers.create', 'kyc.customers.resend', 'kyc.customers.decide'],
  actions: [
    { id: 'kyc.customers.new', label: 'New customer', cell: 'org.onboard_child', command: 'kyc.customers.create', kind: 'primary' },
    { id: 'kyc.customers.resend', label: 'Re-send activation', cell: 'org.onboard_child', command: 'kyc.customers.resend', kind: 'ghost' },
    { id: 'kyc.customers.activate', label: 'Activate customer', cell: 'kyc.approve', command: 'kyc.customers.decide', kind: 'primary' },
    { id: 'kyc.customers.reject', label: 'Reject', cell: 'kyc.reject', command: 'kyc.customers.decide', kind: 'danger' },
  ],
  commandOperations: {
    'kyc.customers.create': 'create',
    'kyc.customers.resend': 'modify',
    'kyc.customers.decide': 'approve',
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  render: KycCustomers,
});

export default kycCustomersWidget;
