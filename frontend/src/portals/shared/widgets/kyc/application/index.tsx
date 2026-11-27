/** kyc.application — the organization's own KYC standing (read-only). */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { KycApplication } from './widget';

const kycApplicationWidget = defineWidget({
  id: 'kyc.application',
  version: '1.0.0',
  label: 'My organization',
  description: 'This organization’s KYC case, verification, risk rating and next periodic review.',
  category: 'Onboarding',
  contexts: ['ANY'],
  requires: ['kyc.review'],
  sources: ['kyc.application.self'],
  commands: [],
  actions: [],
  commandOperations: {},
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  render: KycApplication,
});

export default kycApplicationWidget;
