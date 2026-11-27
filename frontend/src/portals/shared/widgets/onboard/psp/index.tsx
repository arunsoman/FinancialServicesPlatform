/** onboard.psp — platform onboarding of PSP (manufacturer) organizations. */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { OnboardPsp } from './widget';

const onboardPspWidget = defineWidget({
  id: 'onboard.psp',
  version: '1.0.0',
  label: 'PSP onboarding',
  description: 'Create PSP (manufacturer) organizations with their administrator and designated checker.',
  category: 'Onboarding',
  contexts: ['PLATFORM'],
  requires: ['org.create'],
  sources: ['onboard.psp.list'],
  commands: ['onboard.psp.create'],
  actions: [{ id: 'org.create', label: 'New organization', cell: 'org.create', command: 'onboard.psp.create', kind: 'primary' }],
  commandOperations: { 'onboard.psp.create': 'create' },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  render: OnboardPsp,
});

export default onboardPspWidget;
