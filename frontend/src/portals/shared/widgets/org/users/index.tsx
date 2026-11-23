import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { OrgUsers } from './widget';

const widget = defineWidget({
  id: 'org.users',
  version: '1.0.0',
  label: 'Organization users',
  description: 'List users in the current organization with their roles and status.',
  category: 'Operations',
  contexts: ['ANY'],
  requires: ['user.manage'],
  sources: ['org.users.list'],
  commands: [],
  actions: [],
  commandOperations: {},
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  render: OrgUsers,
});

export default widget;
