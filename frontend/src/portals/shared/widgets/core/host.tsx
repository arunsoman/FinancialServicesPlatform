/**
 * WidgetHost — the runtime that mounts one placed widget:
 *   permission gate (live lane gates on the session; a host given fixed `cells` — the
 *   widget workbench's frames — gates on exactly those, so access can be previewed);
 *   port wiring (GatewayDataPort live / FixtureDataPort fixture) — the ONE network path;
 *   context propagation (portal/org/selection/tier).
 * The widget component drives its own data lifecycle with usePortData / TanStack Query.
 */
import { useMemo } from 'react';
import type { ReactNode } from 'react';
import type { WidgetDef } from './manifest';
import { requiresMet } from './manifest';
import { useSession } from '../../web-core/session-core';
import { WidgetContextProvider } from './context';
import { GatewayDataPort, FixtureDataPort } from './ports';
import type { CallRecord, FixtureScenario, FixtureSet } from './ports';

export interface WidgetHostProps {
  def: WidgetDef;
  selection?: Record<string, string>;
  onSelect?: (key: string, value: string) => void;
  params?: Record<string, string>;
  mode?: 'live' | 'fixture';
  fixtures?: FixtureSet;
  scenario?: FixtureScenario;
  /** no permission gate at all (fixture previews where access is not the question) */
  ungated?: boolean;
  /** fixed access: gate on these keys instead of the session's (workbench frames) */
  cells?: readonly string[];
  /** context overrides (workbench frames); default from the session */
  portal?: string;
  orgId?: string | null;
  /** simulated fixture latency */
  latencyMs?: number;
  config?: Record<string, unknown>;
  onCall?: (r: CallRecord) => void;
  /** rendered instead of the widget when the gate refuses (default: nothing) */
  forbidden?: ReactNode;
}

export function WidgetHost(props: WidgetHostProps): ReactNode {
  const { def, mode = 'live' } = props;
  const session = useSession();
  const held = props.cells ?? session.permissions;
  const gated = !props.ungated && (mode === 'live' || props.cells !== undefined);
  const portal = props.portal ?? session.user?.tenantType ?? 'ANY';
  // When acting ("sudo into a tenant", plans/act-as-tenant-plan.md) the person's widgets
  // operate on the TARGET tenant — stepping in means the queue/orgs/users all read that
  // tenant, not the home one. An explicit props.orgId always wins.
  const actingTenantId = session.actAs?.targetTenantId ?? null;
  const orgId = props.orgId !== undefined ? props.orgId : (actingTenantId ?? session.user?.tenantId ?? null);

  const ctx = useMemo(
    () => ({
      portal,
      orgId,
      screenKey: def.id,
      params: props.params ?? {},
      selection: props.selection ?? {},
      select: props.onSelect ?? (() => {}),
      permissions: held,
    }),
    [portal, orgId, def.id, props.params, props.selection, props.onSelect, held],
  );

  const port = useMemo(
    () =>
      mode === 'fixture'
        ? new FixtureDataPort(props.fixtures ?? {}, props.scenario ?? 'default', props.onCall, props.latencyMs ?? 0, held)
        : new GatewayDataPort(() => ({ orgId, portal, params: props.params ?? {} }), props.onCall),
    [mode, props.fixtures, props.scenario, props.onCall, props.latencyMs, orgId, portal, props.params, held],
  );

  if (gated && !requiresMet(def, held)) return props.forbidden ?? null;
  if (props.cells === undefined && def.size && def.size.xs.kind === 'hidden') return null; // layout choice, not permission

  const Runtime = def.render;
  return (
    <WidgetContextProvider value={ctx} port={port}>
      {Runtime && <Runtime mode={mode} config={props.config ?? {}} context={ctx as never} port={port} state="loading" />}
    </WidgetContextProvider>
  );
}
