import { Button, EmptyState, Panel } from '../../ui/atoms';

export interface StatePanelProps {
  kind: 'loading' | 'forbidden' | 'error' | 'empty';
  title: string;
  hint?: string;
  onRetry?: () => void;
}

/** The four non-ready widget states, rendered the same way everywhere (skeletons, not spinners). */
export function StatePanel({ kind, title, hint, onRetry }: StatePanelProps) {
  if (kind === 'loading') {
    return (
      <Panel className="p-4" aria-busy="true">
        <div className="mb-3 h-5 w-48 rounded bg-raised" />
        <div className="grid gap-2">
          {[0, 1, 2].map((n) => <div key={n} className="h-9 rounded-lg bg-raised" />)}
        </div>
      </Panel>
    );
  }
  return (
    <Panel className="p-4">
      <EmptyState title={title} hint={hint} />
      {kind === 'error' && onRetry && (
        <div className="mt-3 flex justify-center">
          <Button variant="ghost" onClick={onRetry}>Try again</Button>
        </div>
      )}
    </Panel>
  );
}
