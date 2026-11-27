import { NavLink } from 'react-router-dom';
import { useConnection } from './connection';
import { useTheme, type ThemeChoice } from './theme';
import { pendingCountText, usePendingApprovalCount } from './pending-approvals';

/** The bar at the top of the workbench: signed in or not, as whom, and what to do about it. */
export function ConnectionBar() {
  const c = useConnection();
  const { theme, setTheme, resolved } = useTheme();
  const on = c.status === 'connected';
  // Four-eyes queue status: how many requests are waiting on a checker (polled, live).
  const pending = usePendingApprovalCount();
  const pendingText = pendingCountText(pending.data);
  const lastChecked = pending.dataUpdatedAt ? new Date(pending.dataUpdatedAt).toLocaleTimeString() : null;
  return (
    <div className="conn" data-connection={c.status}>
      <span className={on ? 'dot on' : 'dot'} data-state={resolved} />
      <b>{on ? 'FSP API' : 'Fixtures'}</b>
      <select
        className="small"
        aria-label="Colour theme"
        title={`Theme: ${theme === 'system' ? `system (${resolved})` : theme}`}
        value={theme}
        onChange={(e) => setTheme(e.target.value as ThemeChoice)}
      >
        <option value="system">System ({resolved})</option>
        <option value="light">Light</option>
        <option value="dark">Dark</option>
      </select>
      <span className="mono dim small">same-origin /api → BFF → gateway → core</span>
      <span className="grow" />
      {c.status === 'checking' && <span className="small dim" role="status">Checking the session…</span>}
      {c.status === 'connected' && (
        <>
          <NavLink to="/w/approvals.queue" className="badge" title={
            pending.isPending
              ? 'Pending approvals: checking…'
              : pending.isError
                ? 'Pending approvals: count unavailable (load failed — will retry)'
                : `Pending approvals: ${pendingText} · checked ${lastChecked} · open the queue`
          }>
            Pending approvals: <b aria-live="polite">{pendingText}</b>
          </NavLink>
          <label className="chk small">
            <input
              type="checkbox"
              checked={c.useRealAccess}
              onChange={() => c.setUseRealAccess(!c.useRealAccess)}
            />
            use my real permissions and org
          </label>
          <button type="button" className="lnk" onClick={() => void c.refresh()}>Refresh</button>
          {/* who you are + how to leave stay together, as one unit, never split across a wrap */}
          <span className="account">
            <span className="small">
              signed in as <b>{c.who}</b> · org <span className="mono">{c.orgName ?? c.orgId ?? '—'}</span> ·{' '}
              {c.permissions.length} permissions
            </span>
            <button type="button" className="btn" data-action="workbench.sign-out" onClick={() => void c.signOut()}>
              Sign out
            </button>
          </span>
        </>
      )}
      {c.status === 'signed-out' && (
        <>
          <span className="small warn">Fixtures only. Sign in for live data, the registry server and publishing.</span>
          <button type="button" className="btn primary" onClick={c.signIn}>Sign in</button>
        </>
      )}
      {c.status === 'not-eligible' && (
        <>
          <span className="small bad">
            Signed in as {c.who}, but the workbench is for platform staff. Fixtures still work.
          </span>
          <button type="button" className="btn" data-action="workbench.sign-out" onClick={() => void c.signOut()}>
            Sign out
          </button>
        </>
      )}
    </div>
  );
}
