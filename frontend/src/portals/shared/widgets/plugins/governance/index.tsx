/** plugins.governance — plugin types + installations (GAP-4); writes are four-eyes requests. */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { PluginsGovernance } from './widget';

const pluginsGovernanceWidget = defineWidget({
  id: 'plugins.governance',
  version: '1.0.0',
  label: 'Plugins',
  description: 'Registered plugin types and each organization’s installations with readiness gates.',
  category: 'Platform',
  contexts: ['PLATFORM'],
  requires: ['plugin.manage'],
  sources: ['plugins.governance.types', 'plugins.governance.orgs', 'plugins.governance.installations'],
  commands: ['plugins.governance.register', 'plugins.governance.install'],
  actions: [
    { id: 'plugin.register', label: 'Register plugin', cell: 'plugin.register', command: 'plugins.governance.register', kind: 'primary' },
    { id: 'plugin.install', label: 'Request installation', cell: 'plugin.install', command: 'plugins.governance.install', kind: 'primary' },
  ],
  commandOperations: { 'plugins.governance.register': 'create', 'plugins.governance.install': 'create' },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  render: PluginsGovernance,
});

export default pluginsGovernanceWidget;
