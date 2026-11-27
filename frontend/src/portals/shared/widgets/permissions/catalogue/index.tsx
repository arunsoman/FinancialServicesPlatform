/** permissions.catalogue — the global permission catalog, with the viewer's held keys marked. */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { PermissionsCatalogue } from './widget';

const permissionsCatalogueWidget = defineWidget({
  id: 'permissions.catalogue',
  version: '1.0.0',
  label: 'All permissions',
  description: 'Every permission key, its risk class and owning component; the ones you hold are marked.',
  category: 'Access',
  contexts: ['ANY'],
  requires: ['audit.view', 'role.manage', 'role.view', 'kyc.review'],
  sources: ['permissions.catalogue.list'],
  commands: [],
  actions: [],
  commandOperations: {},
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  render: PermissionsCatalogue,
});

export default permissionsCatalogueWidget;
