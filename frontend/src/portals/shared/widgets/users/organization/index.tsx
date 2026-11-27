/**
 * users.organization — ONE widget for the whole user lifecycle of an organization:
 * list · search/filter · view · add · modify (details + roles) · deactivate · activate · remove.
 *
 * Every action is gated server-side by `user.manage` on the target org (core
 * UserAdminController); self-lockout and role escalation are refused by core and
 * pre-empted here. List filters and the open user live in the URL
 * (ui-std §7) so a view can be linked and survives reload.
 */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { UsersOrganization } from './widget';

export interface UsersOrganizationConfig {
  /** own: the signed-in org (or the org acted as). selected: the org picked on the page (?org=). */
  scope: 'own' | 'selected';
}

const usersOrganizationWidget = defineWidget<UsersOrganizationConfig>({
  id: 'users.organization',
  version: '2.3.0',
  label: 'User management',
  description: 'List, view, add, modify, deactivate, activate and remove the users of an organization. New users activate from an IdP link / SMS code and set their own password.',
  category: 'Access',
  contexts: ['ANY'],
  requires: ['user.manage'],
  sources: ['users.list', 'users.get', 'users.roles'],
  commands: ['user.create', 'user.update', 'user.deactivate', 'user.activate', 'user.remove', 'user.activation.resend'],
  actions: [
    { id: 'user.create', label: 'Add user', cell: 'user.manage', command: 'user.create', kind: 'primary' },
    { id: 'user.update', label: 'Edit user', cell: 'user.manage', command: 'user.update' },
    { id: 'user.deactivate', label: 'Deactivate', cell: 'user.manage', command: 'user.deactivate', kind: 'ghost' },
    { id: 'user.activate', label: 'Activate', cell: 'user.manage', command: 'user.activate', kind: 'ghost' },
    { id: 'user.remove', label: 'Remove', cell: 'user.manage', command: 'user.remove', kind: 'danger' },
    { id: 'user.activation.resend', label: 'Re-send activation', cell: 'user.manage', command: 'user.activation.resend', kind: 'ghost' },
  ],
  commandOperations: {
    'user.create': 'create',
    'user.update': 'modify',
    'user.deactivate': 'modify',
    'user.activate': 'modify',
    'user.remove': 'modify',
    'user.activation.resend': 'modify',
  },
  // full width by default; may sit beside a list (min 6 of 12) as the detail of a picked org
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6, 12), lg: span.lg(6, 12) },
  hideOn: ['forbidden'],
  config: {
    scope: {
      type: 'enum', options: ['own', 'selected'] as const, default: 'own', label: 'Organization',
      help: "own: the signed-in org (or the one acted as). selected: the org picked on this screen — read-only until you act as its admin.",
    },
  },
  render: UsersOrganization,
});

export default usersOrganizationWidget;
