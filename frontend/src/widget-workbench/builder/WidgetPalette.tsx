import { readProblem } from '../../portals/shared/web-core/api';
import type { WidgetDef } from '../../portals/shared/widgets/core/manifest';
import { useConnection } from '../connection';
import { requiresText } from '../model';
import { useRegisterWidget, useServerWidgets } from '../registration';
import { DND_WIDGET } from './dnd';

export interface WidgetPaletteProps {
  widgets: readonly WidgetDef[];
  portalLabel: string;
}

/** This build's widgets that work in the portal — drag one onto the screen. */
export function WidgetPalette({ widgets, portalLabel }: WidgetPaletteProps) {
  const conn = useConnection();
  const server = useServerWidgets();
  const register = useRegisterWidget();
  const held = server.data ? new Map(server.data.map((w) => [w.id, w.version])) : null;
  return (
    <>
      <div className="pane-h">
        <b>Widgets</b>
        <span className="dim small">{portalLabel}</span>
      </div>
      <div className="palette">
        {widgets.map((w) => (
          <div
            key={w.id}
            className="pal"
            draggable
            title="Drag onto the screen"
            onDragStart={(e) => {
              e.dataTransfer.setData(DND_WIDGET, w.id);
              e.dataTransfer.effectAllowed = 'copy';
            }}
          >
            <b>{w.label}</b>
            <span className="dim small">{w.category ?? 'Other'} · {w.id}@{w.version}</span>
            <span className="mono small dim">{requiresText(w)}</span>
            {held && held.get(w.id) !== w.version && (
              <span className="small warn">
                {held.has(w.id) ? `system has v${held.get(w.id)}` : 'not in the system yet'}
                <button
                  type="button"
                  className="lnk"
                  disabled={!conn.canBuild || register.isPending}
                  title={conn.canBuild ? `Register ${w.id}@${w.version}` : 'You need the ui.widget.register permission'}
                  onClick={() => register.mutate(w)}
                >
                  {register.isPending && register.variables?.id === w.id ? 'Registering…' : 'Register'}
                </button>
              </span>
            )}
          </div>
        ))}
        {register.error && <div className="small bad" role="alert">Register failed: {readProblem(register.error).detail}</div>}
        {widgets.length === 0 && <div className="dim small pad-s">No registered widget works in {portalLabel} yet.</div>}
      </div>
    </>
  );
}
