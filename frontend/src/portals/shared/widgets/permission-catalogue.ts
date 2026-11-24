/**
 * THE FSP permission catalogue as the widget layer knows it — mirrors the
 * core `permissions` table (V2 seed; verified against the live DB) plus the
 * `idp.manage` row from V6, the plugin/act-as keys (V11/V14) and `role.view` (V19). tools/widgets.ts `check` validates every widget
 * action cell against this list (decision 6: permission coherence blocks
 * merges). The runtime truth remains the server (/api/permissions).
 */
export const PERMISSION_CATALOGUE = [
  'audit.view',
  'bank.account.approve',
  'bank.account.create',
  'bank.account.view',
  'gl.account.approve',
  'gl.account.create',
  'gl.account.view',
  'idp.manage',
  // invoicing (billing.invoices) — V40 + V41; invoice.void.approve decides a void in the approvals queue
  'invoice.collect',
  'invoice.create',
  'invoice.pay',
  'invoice.view',
  'invoice.void',
  'invoice.void.approve',
  'je.approve',
  'je.create',
  'je.view',
  'kyc.approve',
  'kyc.reject',
  'kyc.review',
  'org.activate',
  'org.approve_child',
  'org.create',
  'org.onboard_child',
  'period.close',
  'period.open',
  'platform.bootstrap',
  'plugin.install',
  'plugin.manage',
  'plugin.register',
  'role.manage',
  'role.view',
  'screening.run',
  'tenant.act_as',
  'ui.layout.manage',
  'ui.widget.register',
  'user.manage',
] as const;

export function knownKey(key: string): boolean {
  return (PERMISSION_CATALOGUE as readonly string[]).includes(key);
}
