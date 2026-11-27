/**
 * Screen registry — THE map from menu point → coded screen (the widget
 * compositions arrive with layouts; each screen here is a thin mount point
 * that will host the same widgets the layout renderer places).
 */
import type { CSSProperties } from 'react';
import type { MenuItem, PortalId } from './shared/web-core/menu';
import { useSession } from './shared/web-core/session-core';
import { PageHeader, Panel, EmptyState } from './shared/ui/atoms';
import { WidgetHost } from './shared/widgets/core/host';
import { getWidget } from './shared/widgets/registry';
import { usePageSelection } from './shared/widgets/core/page-selection';

export interface ScreenProps {
  portalId: PortalId;
  link: string;
  widgets: MenuItem['widgets'];
  label: string;
  config?: MenuItem['config'];
  span?: MenuItem['span'];
}

/* ---------------------------------------------------------------- overview */
export function OverviewScreen({ widgets }: ScreenProps) {
  const { user, tenant } = useSession();
  return (
    <>
      <PageHeader title="Overview" subtitle={tenant?.legalName ?? user?.tenantName ?? ' '} />
      <WidgetGrid widgets={widgets} />
    </>
  );
}

/* ------------------------------------------------------- widget placeholder */
export function WidgetScreen({ label, widgets, config, span }: ScreenProps & { label: string }) {
  return (
    <>
      <PageHeader title={label} subtitle="Composed from the widgets registry — the route and its permissions are already governed." />
      <WidgetGrid widgets={widgets} config={config} span={span} />
    </>
  );
}

/** The 12-column grid of a screen's widgets; each mount carries data-widget for tests/diagnostics. */
function WidgetGrid({ widgets, config, span }: { widgets: MenuItem['widgets']; config?: MenuItem['config']; span?: MenuItem['span'] }) {
  const { selection, onSelect } = usePageSelection();
  return (
    <>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-12">
        {widgets.map((id) => {
          const def = getWidget(id);
          if (!def) {
            return (
              <Panel className="p-4 md:col-span-12" key={id}>
                <EmptyState
                  title={`Widget "${id}" not registered yet`}
                  hint="Built next per §6.3 of the portals plan — manifest → fixtures → component → registry check."
                />
              </Panel>
            );
          }
          // honour the manifest's declared lg span (12-col grid); single column below md
          const lg = def.size.lg;
          const want = span?.[id]?.lg;
          const cols = lg.kind === 'span' ? Math.max(lg.min, Math.min(lg.max, want ?? lg.default)) : 12;
          return (
            <div key={id} data-widget={id} className="min-w-0 md:[grid-column:span_var(--w)_/_span_var(--w)]" style={{ '--w': cols } as CSSProperties}>
              <WidgetHost def={def} config={config?.[id]} selection={selection} onSelect={onSelect} />
            </div>
          );
        })}
      </div>
    </>
  );
}
