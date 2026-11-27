import { defineWidget } from '../../core/manifest';
import { span } from '../../core/grid';
import { InventoryPlaceholder } from './widget';

/**
 * inventory.placeholder — reserved landing screen for the Inventory plugin plane.
 * Intentionally shows nothing (an empty pane): the menu is published ahead of the
 * inventory widgets so org portals light up their Inventory section now.
 */
const inventoryPlaceholderWidget = defineWidget({
  id: 'inventory.placeholder',
  version: '1.0.0',
  label: 'Inventory',
  description: 'Inventory — nothing here yet.',
  category: 'Inventory',
  contexts: ['ANY'],
  requires: [],
  sources: [],
  commands: [],
  actions: [],
  commandOperations: {},
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: [],
  render: InventoryPlaceholder,
});

export default inventoryPlaceholderWidget;
