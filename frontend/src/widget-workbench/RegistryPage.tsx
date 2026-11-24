import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { readProblem } from '../portals/shared/web-core/api';
import { WIDGETS } from '../portals/shared/widgets/registry';
import { useConnection } from './connection';
import { useRegisterWidget, useServerWidgets } from './registration';
import { REGISTRY_ORDERS, requiresText, sortByCreated, spansText } from './model';
import type { RegistryOrder } from './model';


/**
 * What the portal builder can discover (SME registry-page): the widgets this build
 * registers, and what core's registry holds (GET /api/ui/widgets). Registering is the
 * `ui.widget.register` operation; core re-checks the permission on every call.
 */
export function RegistryPage() {
  const conn = useConnection();
  const connected = conn.status === 'connected';

  const server = useServerWidgets();
  const register = useRegisterWidget();

  const onServer = new Map((server.data ?? []).map((w) => [w.id, w]));
  const local = new Map(WIDGETS.map((w) => [w.id, w.version]));
  const serverProblem = server.error ? readProblem(server.error) : null;

  /** Find one of 18 dense rows without scrolling the whole table (dashboard filter pattern). */
  const categories = useMemo(
    () => [...new Set(WIDGETS.map((w) => w.category ?? '—'))].sort((a, b) => a.localeCompare(b)),
    [],
  );
  const [needle, setNeedle] = useState('');
  const [cat, setCat] = useState('all');
  // the order lives in the URL (?sort=created-desc) so a sorted view can be linked / reloaded
  const [q, setQ] = useSearchParams();
  const askedOrder = q.get('sort') as RegistryOrder | null;
  const order: RegistryOrder = REGISTRY_ORDERS.some((o) => o.value === askedOrder) ? askedOrder! : 'build';
  const setOrder = (o: RegistryOrder) =>
    setQ((p) => {
      const n = new URLSearchParams(p);
      if (o === 'build') n.delete('sort');
      else n.set('sort', o);
      return n;
    }, { replace: true });
  const createdOf = (id: string) => onServer.get(id)?.createdAt ?? null;
  const shown = useMemo(() => {
    const n = needle.trim().toLowerCase();
    const filtered = WIDGETS.filter((w) => (cat === 'all' || (w.category ?? '—') === cat)).filter(
      (w) =>
        !n ||
        [w.id, w.label, w.description ?? '', requiresText(w), ...(w.actions ?? []).map((a) => `${a.label} ${a.cell}`)]
          .join(' ').toLowerCase().includes(n),
    );
    return sortByCreated(filtered, order, (w) => server.data?.find((s) => s.id === w.id)?.createdAt);
  }, [needle, cat, order, server.data]);
  const serverRows = useMemo(
    () => (server.data ? sortByCreated(server.data, order, (s) => s.createdAt) : undefined),
    [server.data, order],
  );

  return (
    <>
      <h1>Widget registry</h1>
      <p className="dim">
        This build: {WIDGETS.length} widget(s), discovered from <span className="mono">shared/widgets/*/*/index.tsx</span>.
        Readiness per widget: <span className="mono">bun run widgets:check</span>.
      </p>
      <div className="row-b" role="search" aria-label="Filter widgets">
        <input
          type="search"
          placeholder="Search label, id, cell…"
          aria-label="Search widgets"
          value={needle}
          onChange={(e) => setNeedle(e.target.value)}
        />
        <select aria-label="Filter by category" value={cat} onChange={(e) => setCat(e.target.value)}>
          <option value="all">All categories</option>
          {categories.map((c) => <option key={c} value={c}>{c}</option>)}
        </select>
        <select
          aria-label="Sort widgets"
          value={order}
          onChange={(e) => setOrder(e.target.value as RegistryOrder)}
          title={connected ? 'Creation time = when core first registered the widget' : 'Sign in to sort by creation time (core holds it)'}
        >
          {REGISTRY_ORDERS.map((o) => (
            <option key={o.value} value={o.value} disabled={o.value !== 'build' && !connected}>{o.label}</option>
          ))}
        </select>
        <span className="dim small" role="status">
          {shown.length === WIDGETS.length ? 'all' : `${shown.length} of ${WIDGETS.length}`} shown
        </span>
        {shown.length === 0 && <span className="warn small">No widget matches — clear the search.</span>}
      </div>
      <table>
        <thead>
          <tr>
            <th>Widget</th><th>Category</th><th>Portals</th><th>Shows with</th><th>Actions</th><th>Reads / writes</th>
            <th>Span xs · sm · md · lg</th><th>Created</th><th>Registry server</th>
          </tr>
        </thead>
        <tbody>
          {shown.map((w) => {
            const s = onServer.get(w.id);
            const current = s && s.version === w.version;
            return (
              <tr key={w.id}>
                <td>
                  <Link className="lnk" to={`/w/${w.id}`}>{w.label}</Link>
                  <div className="mono dim small">{w.id}@{w.version}</div>
                </td>
                <td>{w.category ?? '—'}</td>
                <td className="small">{w.contexts.join(', ')}</td>
                <td className="mono small">{requiresText(w)}</td>
                <td className="small">
                  {(w.actions ?? []).map((a) => (
                    <div key={a.id}>{a.label} <span className="mono dim">{a.cell}</span></div>
                  ))}
                </td>
                <td className="mono small">{w.sources?.length ?? 0} sources · {w.commands?.length ?? 0} commands</td>
                <td className="mono small">{spansText(w)}</td>
                <td className="small" data-created={createdOf(w.id) ?? ''}>
                  {createdOf(w.id)
                    ? new Date(createdOf(w.id)!).toLocaleString()
                    : <span className="dim">{connected && !onServer.has(w.id) ? 'not registered' : '—'}</span>}
                </td>
                <td className="small">
                  {!connected ? (
                    <span className="dim">sign in to compare</span>
                  ) : current ? (
                    <span className="ok">registered</span>
                  ) : (
                    <>
                      <span className="warn">{s ? `server has ${s.version}` : 'not registered'}</span>{' '}
                      <button
                        type="button"
                        className="lnk"
                        disabled={!conn.canBuild || register.isPending}
                        title={conn.canBuild ? undefined : 'Needs ui.widget.register'}
                        onClick={() => register.mutate(w)}
                      >
                        Register {w.version}
                      </button>
                    </>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {register.error && <p className="bad small">Register failed: {readProblem(register.error).detail}</p>}

      <h2>Registry server</h2>
      <p className="dim">
        What core holds (<span className="mono">GET /v1/ui/widgets</span>, operation{' '}
        <span className="mono">ui.widget.list</span>). The builder&apos;s palette is this build; the server list is what
        published layouts may reference.
      </p>
      {!connected && <p className="warn small">Sign in (bar above) to read the registry server.</p>}
      {connected && (
        <div className="row-b">
          <button type="button" className="btn" onClick={() => void server.refetch()} disabled={server.isFetching}>
            {server.isFetching ? 'Asking…' : 'Ask the registry server again'}
          </button>
        </div>
      )}
      {serverProblem && (
        <p className="bad">
          {serverProblem.status === 403 ? `Signed in, but not allowed: ${serverProblem.detail}` : serverProblem.detail}
        </p>
      )}
      {serverRows && (
        <table>
          <thead>
            <tr><th>Widget</th><th>Version</th><th>Bundle</th><th>Portals</th><th>Requires</th><th>Created</th><th>Updated</th><th>In this build?</th></tr>
          </thead>
          <tbody>
            {serverRows.map((s) => (
              <tr key={s.id}>
                <td>{s.label} <span className="mono dim small">{s.id}</span></td>
                <td className="mono">{s.version}</td>
                <td className="mono small">{s.bundle}</td>
                <td className="small">{s.portals.join(', ')}</td>
                <td className="mono small">{s.requires.join(', ')}</td>
                <td className="small">{s.createdAt ? new Date(s.createdAt).toLocaleString() : '—'}</td>
                <td className="small">{new Date(s.updatedAt).toLocaleString()}</td>
                <td>{local.has(s.id) ? `yes (${local.get(s.id)})` : <span className="warn">no</span>}</td>
              </tr>
            ))}
            {serverRows.length === 0 && (
              <tr><td colSpan={8} className="dim">Nothing registered yet.</td></tr>
            )}
          </tbody>
        </table>
      )}
    </>
  );
}
