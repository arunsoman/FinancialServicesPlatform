/**
 * org.branches — the branches of an organization: list · search · sort · filter · add ·
 * rename · delete, every write four-eyes (maker files → a different checker decides).
 * Gated by the Role Management keys: branches scope roles (rbac.matrix picks them).
 * List filters and the open branch live in the URL (ui-std §7, §10.6).
 */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { OrgBranches } from './widget';

const orgBranchesWidget = defineWidget({
  id: 'org.branches',
  version: '1.0.0',
  label: 'Branches',
  description: 'Create, rename and delete the branches of an organization — each change is approved by a second person. Lists who created and who approved every branch.',
  category: 'Access',
  contexts: ['ANY'],
  requires: ['role.view', 'role.manage'],
  sources: ['org.branches.list', 'org.branches.get'],
  commands: ['org.branches.create', 'org.branches.update', 'org.branches.delete', 'org.branches.approve', 'org.branches.reject'],
  actions: [
    { id: 'branch.create', label: 'New branch', cell: 'role.manage', command: 'org.branches.create', kind: 'primary' },
    { id: 'branch.edit', label: 'Rename', cell: 'role.manage', command: 'org.branches.update', kind: 'ghost' },
    { id: 'branch.delete', label: 'Delete', cell: 'role.manage', command: 'org.branches.delete', kind: 'danger' },
    { id: 'branch.approve', label: 'Approve', cell: 'role.manage', command: 'org.branches.approve', kind: 'primary' },
    { id: 'branch.reject', label: 'Reject', cell: 'role.manage', command: 'org.branches.reject', kind: 'danger' },
  ],
  commandOperations: {
    'org.branches.create': 'create',
    'org.branches.update': 'modify',
    'org.branches.delete': 'modify',
    'org.branches.approve': 'approve',
    'org.branches.reject': 'approve',
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  render: OrgBranches,
});

export default orgBranchesWidget;
