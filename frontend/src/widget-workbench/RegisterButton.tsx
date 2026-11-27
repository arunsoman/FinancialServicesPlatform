import { readProblem } from '../portals/shared/web-core/api';
import type { WidgetDef } from '../portals/shared/widgets/core/manifest';
import { useConnection } from './connection';
import { registrationState, useRegisterWidget, useServerWidgets } from './registration';

/**
 * The widget page's "put it in the system" button: registers this build's widget with core
 * (ui.widget.register) in one press. Shows where the widget stands — registered, an older
 * version on the server, or not registered — and why the button is off when it is.
 */
export function RegisterButton({ def }: { def: WidgetDef }) {
  const conn = useConnection();
  const server = useServerWidgets();
  const register = useRegisterWidget();
  const connected = conn.status === 'connected';
  const state = registrationState(def, connected, {
    data: server.data,
    isLoading: server.isLoading,
    error: server.error,
    detail: server.error ? readProblem(server.error).detail : undefined,
  });

  if (state.kind === 'signed-out') {
    return (
      <div className="small" data-registration="signed-out">
        <button type="button" className="btn" onClick={conn.signIn}>Sign in to register</button>
      </div>
    );
  }
  if (state.kind === 'registered') {
    return (
      <div className="small" data-registration="registered" role="status">
        <span className="ok">✓ Registered in the system</span> <span className="mono dim">v{state.version}</span>
      </div>
    );
  }
  const label = state.kind === 'outdated' ? `Register v${def.version} (system has v${state.serverVersion})` : `Register in the system`;
  const blocked = !conn.canBuild ? 'You need the ui.widget.register permission' : null;
  return (
    <div className="small" data-registration={state.kind}>
      <button
        type="button"
        className="btn primary"
        disabled={!!blocked || register.isPending || state.kind === 'loading' || state.kind === 'unavailable'}
        title={blocked ?? undefined}
        onClick={() => register.mutate(def)}
      >
        {register.isPending ? 'Registering…' : label}
      </button>
      {blocked && <div className="dim">{blocked}</div>}
      {state.kind === 'unavailable' && <div className="warn">Registry unavailable: {state.detail}</div>}
      {register.error && <div className="bad" role="alert">Register failed: {readProblem(register.error).detail}</div>}
    </div>
  );
}
