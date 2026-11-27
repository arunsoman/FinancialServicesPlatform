import { EmptyState, PageHeader, Panel } from '../../../ui/atoms';

/** Empty by design — the Inventory plane is published before its widgets land. */
export function InventoryPlaceholder() {
  return (
    <Panel className="p-4">
      <PageHeader title="Inventory" />
      <EmptyState title="Nothing here yet" />
    </Panel>
  );
}
