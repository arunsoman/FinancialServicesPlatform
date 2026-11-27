/**
 * users.reset — Reset Password: the organization's users (server-paged, searchable,
 * sortable) with the one credential action core allows each — a four-eyes reset request (the
 * IdP then emails a one-time link after a second approver accepts) or, for someone who never
 * activated, their activation again. No password is ever entered, seen or set by staff (FAPI 2.0).
 */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { PasswordReset } from './widget';

const passwordResetWidget = defineWidget({
  id: 'users.reset',
  version: '1.0.0',
  label: 'Reset password',
  description: 'Request a four-eyes password reset (one-time email link from the IdP) or re-send an activation.',
  category: 'Access',
  contexts: ['ANY'],
  requires: ['user.manage'],
  sources: ['users.list'],
  commands: ['user.password.reset.request', 'user.activation.resend'],
  actions: [
    { id: 'user.password.reset.request', label: 'Request reset', cell: 'user.manage', command: 'user.password.reset.request', kind: 'ghost' },
    { id: 'user.activation.resend', label: 'Re-send activation', cell: 'user.manage', command: 'user.activation.resend', kind: 'ghost' },
  ],
  commandOperations: {
    'user.password.reset.request': 'modify',
    'user.activation.resend': 'modify',
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  render: PasswordReset,
});

export default passwordResetWidget;
