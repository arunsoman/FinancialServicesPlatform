import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { PasswordResetWidget } from './widget';

export interface PasswordResetConfig {
  scope: 'own';
  mode: 'reset';
}

const passwordResetWidget = defineWidget<PasswordResetConfig>({
  id: 'users.resetpassword',
  version: '1.0.0',
  label: 'Reset password',
  description: 'Select an organization user and request a four-eyes password reset.',
  category: 'Access',
  contexts: ['PLATFORM'],
  requires: ['user.manage'],
  sources: ['users.list', 'users.get'],
  commands: ['user.password-reset'],
  actions: [
    { id: 'user.password-reset', label: 'Reset password', cell: 'user.manage', command: 'user.password-reset', kind: 'ghost' },
  ],
  commandOperations: { 'user.password-reset': 'modify' },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6, 12), lg: span.lg(6, 12) },
  hideOn: ['forbidden'],
  config: {
    scope: {
      type: 'enum',
      options: ['own'] as const,
      default: 'own',
      label: 'Organization',
      help: "The signed-in platform organization's users.",
    },
    mode: {
      type: 'enum',
      options: ['reset'] as const,
      default: 'reset',
      label: 'Screen mode',
      help: 'Show only user selection and password-reset actions.',
    },
  },
  render: PasswordResetWidget,
});

export default passwordResetWidget;
