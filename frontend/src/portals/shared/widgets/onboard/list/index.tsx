/**
 * onboard.list — the onboarding organization directory (platform root):
 * paginated, server-side searchable + sortable list of organizations; click one
 * to open its KYC screen. Gated on org.create (the platform creates tenants).
 */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { OnboardList } from './widget';

export interface OnboardListConfig {
  /** own: the signed-in org (or the one acted as). selected: the org picked on this screen (?org=). */
  scope: 'own' | 'selected';
}

const onboardListWidget = defineWidget<OnboardListConfig>({
  id: 'onboard.list',
  version: '1.1.0',
  label: 'Organizations',
  description: 'Paginated, searchable, sortable directory of organizations; click one to open its KYC screen.',
  category: 'Onboarding',
  contexts: ['PLATFORM'],
  // kyc.review is a supporting read: the widget joins orgs with their KYC cases (kyc.cases.page,
  // fixture-server guard) — every platform/tenant role that operates the directory holds it.
  // Without it in `requires` the workbench cell picker can never satisfy the join → permanent 403.
  requires: ['org.create', 'kyc.review'],
  sources: ['onboard.orgs.list', 'kyc.orgs.self', 'kyc.cases.page'],
  commands: [],
  actions: [],
  commandOperations: {},
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  config: {
    scope: {
      type: 'enum', options: ['own', 'selected'] as const, default: 'own', label: 'Organization',
      help: 'own: the signed-in org (or the one acted as). selected: the org picked on this screen — onboarding under it needs acting as its admin.',
    },
  },
  render: OnboardList,
});

export default onboardListWidget;
