/** rbac.matrix — organization roles governance (GAP-1): view, and REQUEST changes (four-eyes). */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { RbacMatrix } from './widget';

const rbacMatrixWidget = defineWidget({
  id: 'rbac.matrix',
  version: '1.1.0',
  label: 'Roles and permissions',
  description: 'An organization’s roles and their permissions; changes are requested and approved four-eyes.',
  category: 'Access',
  contexts: ['ANY'],
  requires: ['role.view', 'role.manage'],
  sources: ['rbac.matrix.roles', 'rbac.matrix.catalog', 'rbac.matrix.org', 'rbac.matrix.menu', 'rbac.matrix.access',
    'rbac.matrix.branches', 'rbac.matrix.departments'],
  commands: ['rbac.matrix.create', 'rbac.matrix.update', 'rbac.matrix.delete'],
  actions: [
    { id: 'role.create', label: 'Add Role', cell: 'role.manage', command: 'rbac.matrix.create', kind: 'primary' },
    { id: 'role.update', label: 'Request change', cell: 'role.manage', command: 'rbac.matrix.update', kind: 'primary' },
    { id: 'role.delete', label: 'Delete role', cell: 'role.manage', command: 'rbac.matrix.delete', kind: 'ghost' },
  ],
  commandOperations: { 'rbac.matrix.create': 'create', 'rbac.matrix.update': 'modify', 'rbac.matrix.delete': 'modify' },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  render: RbacMatrix,
});

export default rbacMatrixWidget;
