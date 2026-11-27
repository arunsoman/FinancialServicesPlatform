import { EmptyState, Panel } from '../../../ui/atoms';

/**
 * Empty by design — the menu point is published (and gated) ahead of the feature behind it.
 * The screen header already carries the menu label.
 */
export function MenuPlaceholder() {
  return (
    <Panel className="p-4">
      <EmptyState title="Nothing here yet" hint="This menu point is reserved; its screen has not been built." />
    </Panel>
  );
}
