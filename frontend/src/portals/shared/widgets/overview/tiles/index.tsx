/** overview.tiles — portal landing tiles (session-derived; no server reads of its own). */
import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { OverviewTiles } from './widget';

const overviewTilesWidget = defineWidget({
  id: 'overview.tiles',
  version: '1.0.0',
  label: 'Overview',
  description: 'Your permissions, your organization and your identity at a glance.',
  category: 'Overview',
  contexts: ['ANY'],
  requires: ['audit.view'],
  sources: [],
  commands: [],
  actions: [],
  commandOperations: {},
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  render: OverviewTiles,
});

export default overviewTilesWidget;
