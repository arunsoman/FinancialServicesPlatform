import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { MenuPlaceholder } from './widget';

/**
 * menu.placeholder — the landing pane of a menu point whose feature has not been built yet
 * (admin console: Reset Password, Customer, Component, Sub Component, Branch, Department).
 * It reads and sends nothing, so it needs no permission: who sees the submenu is decided by the
 * submenu's ACTIVE cell in the role's Permissions Matrix (core /me/menu-access).
 */
const menuPlaceholderWidget = defineWidget({
  id: 'menu.placeholder',
  version: '1.1.0',
  label: 'Coming soon',
  description: 'Reserved menu point — nothing here yet.',
  category: 'Navigation',
  contexts: ['ANY'],
  // nothing to gate: it reads and sends nothing — the submenu's ACTIVE menu cell decides visibility
  requires: [],
  sources: [],
  commands: [],
  actions: [],
  commandOperations: {},
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: [],
  render: MenuPlaceholder,
});

export default menuPlaceholderWidget;
