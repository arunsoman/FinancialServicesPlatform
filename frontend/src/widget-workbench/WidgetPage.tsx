import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { TIERS } from '../portals/shared/widgets/core/grid';
import type { Tier } from '../portals/shared/widgets/core/grid';
import type { ConfigField } from '../portals/shared/widgets/core/manifest';
import { cellsOf } from '../portals/shared/widgets/core/manifest';
import type { CallRecord } from '../portals/shared/widgets/core/ports';
import { SCENARIOS, SCENARIO_EXPECT } from '../portals/shared/widgets/fixtures';
import type { Scenario } from '../portals/shared/widgets/fixtures';
import { getWidget } from '../portals/shared/widgets/registry';
import { useConnection } from './connection';
import type { Geometry } from './measure';
import { FIXTURE_ORG, PORTAL_CONTEXTS, TIER_LABEL, defaultContext } from './model';
import { NetworkTable } from './NetworkTable';
import { RegisterButton } from './RegisterButton';
import { WidgetFrame } from './WidgetFrame';
import type { FrameSetup } from './WidgetFrame';

type FrameApi = { measure: () => Geometry | null; state: () => string };

/**
 * One widget, every tier side by side (SME widget-page). Everything on the page is in the
 * URL, so a failing layout check prints a link that reproduces it here:
 *   /widget-workbench/w/<id>?portal=DISTRIBUTOR&org=…&scenario=empty&cells=none&tier=xs&single=1
 */
export function WidgetPage() {
  const { id = '' } = useParams();
  const [q, setQ] = useSearchParams();
  const conn = useConnection();
  const def = getWidget(id);

  const set = useCallback(
    (key: string, value: string | null) =>
      setQ(
        (p) => {
          const n = new URLSearchParams(p);
          if (value === null) n.delete(key);
          else n.set(key, value);
          return n;
        },
        { replace: true },
      ),
    [setQ],
  );

  const live = q.get('data') === 'live';
  const realAccess = live && conn.status === 'connected' && conn.useRealAccess;
  const portal = q.get('portal') ?? (def ? defaultContext(def) : 'PLATFORM');
  const orgId = q.has('org') ? q.get('org') || null : realAccess ? conn.orgId : FIXTURE_ORG;
  const allCells = useMemo(() => (def ? cellsOf(def) : []), [def]);
  const cellsParam = q.get('cells') ?? 'all';
  const cells = useMemo<readonly string[]>(() => {
    if (realAccess) return conn.permissions;
    if (cellsParam === 'all') return allCells;
    if (cellsParam === 'none') return [];
    return cellsParam.split(',').filter(Boolean);
  }, [realAccess, conn.permissions, cellsParam, allCells]);
  const asked = q.get('scenario') as Scenario | null;
  const scenario: Scenario = asked && SCENARIOS.includes(asked) ? asked : 'default';
  const latency = Number(q.get('latency') ?? 0) || 0;
  const grid = q.get('grid') !== '0';
  const outline = q.get('outline') !== '0';
  const single = q.get('single') === '1';
  const tiers: readonly Tier[] = single ? [((q.get('tier') as Tier | null) ?? 'xs')] : TIERS;

  const configFields = useMemo(
    () => Object.entries(def?.config ?? {}).map(([key, field]) => ({ key, field: field as ConfigField })),
    [def],
  );
  const qs = q.toString();
  const config = useMemo(() => {
    const p = new URLSearchParams(qs);
    const out: Record<string, unknown> = {};
    for (const { key, field } of configFields) {
      const v = p.get(`cfg_${key}`);
      out[key] = v === null ? field.default : field.type === 'number' ? Number(v) : field.type === 'boolean' ? v === 'true' : v;
    }
    return out;
  }, [qs, configFields]);

  const setup = useMemo<FrameSetup | null>(
    () => (def ? { def, portal, orgId, cells, scenario, latency, live, config, grid, outline } : null),
    [def, portal, orgId, cells, scenario, latency, live, config, grid, outline],
  );
  // any change but the overlays rebuilds every frame from scratch (fresh port, cache, URL)
  const frameKey = setup
    ? JSON.stringify([setup.def.id, portal, orgId, cells, scenario, latency, live, config])
    : '';

  const [focus, setFocus] = useState<Tier>('md');
  const [callsByTier, setCallsByTier] = useState<Partial<Record<Tier, CallRecord[]>>>({});
  const onCalls = useCallback((t: Tier, calls: CallRecord[]) => setCallsByTier((m) => ({ ...m, [t]: calls })), []);

  // e2e hook (SME window.__wb): measure / state per tier
  const frames = useRef<Partial<Record<Tier, FrameApi>>>({});
  const register = useCallback((t: Tier, api: FrameApi | null) => {
    if (api) frames.current[t] = api;
    else delete frames.current[t];
  }, []);
  useEffect(() => {
    (window as unknown as { __wb: unknown }).__wb = {
      measure: (t: Tier) => frames.current[t]?.measure() ?? null,
      state: (t: Tier) => frames.current[t]?.state() ?? null,
      ready: () => true,
    };
  }, []);

  if (!def || !setup) {
    return (
      <p className="pad">
        Unknown widget “{id}”. <Link className="lnk" to="/registry">See the registry</Link>.
      </p>
    );
  }

  const roleOf = (c: string) => {
    const a = (def.actions ?? []).find((x) => x.cell === c);
    return a ? `action: ${a.label}` : 'shows the widget';
  };
  const toggleCell = (c: string) => {
    const next = cells.includes(c) ? cells.filter((x) => x !== c) : [...cells, c];
    set('cells', next.length ? next.join(',') : 'none');
  };

  const added = q.get('added');
  const registeredNote = q.get('registered');
  return (
    <>
      {added && (
        <p className="small" role="status">
          <span className="ok">Added from a widget pack: </span><span className="mono">{added}</span>
          {registeredNote && (
            <>
              {' · '}
              <span className={registeredNote.startsWith('Registered') ? 'ok' : 'warn'}>{registeredNote}</span>
            </>
          )}
          {' · '}Next: put it on a portal screen in the <Link className="lnk" to="/builder">Portal builder</Link>.
        </p>
      )}
      <section className="bar">
        <div className="title">
          <h1>
            {def.label} <span className="mono dim">{def.id}@{def.version}</span>
          </h1>
          <p className="dim">{def.description}</p>
          <RegisterButton def={def} />
        </div>

        <div className="controls">
          <label>
            Portal
            <select value={portal} onChange={(e) => set('portal', e.target.value)}>
              {PORTAL_CONTEXTS.map((p) => (
                <option key={p} value={p}>
                  {p}
                  {def.contexts.includes(p) || def.contexts.includes('ANY') ? '' : ' (not in contexts)'}
                </option>
              ))}
            </select>
          </label>
          <label>
            Own org (context.orgId)
            <input
              key={orgId ?? ''}
              defaultValue={orgId ?? ''}
              onBlur={(e) => set('org', e.target.value)}
              placeholder="none"
            />
          </label>
          <label>
            Scenario
            <select value={scenario} onChange={(e) => set('scenario', e.target.value)}>
              {SCENARIOS.map((s) => (
                <option key={s} value={s}>{s} → {SCENARIO_EXPECT[s]}</option>
              ))}
            </select>
          </label>
          <label>
            Data
            <select value={live ? 'live' : 'fixtures'} onChange={(e) => set('data', e.target.value === 'live' ? 'live' : null)}>
              <option value="fixtures">fixtures</option>
              <option value="live">live API</option>
            </select>
          </label>
          {live && conn.status !== 'connected' && <span className="small bad">Live needs a connection: see the bar above.</span>}
          {realAccess && <span className="small ok">Using your real permissions and org.</span>}
          <label>
            Latency
            <select value={latency} onChange={(e) => set('latency', e.target.value)} disabled={live}>
              {[0, 400, 2000].map((l) => <option key={l} value={l}>{l} ms</option>)}
            </select>
          </label>
          <label className="chk">
            <input type="checkbox" checked={grid} onChange={() => set('grid', grid ? '0' : null)} /> column grid
          </label>
          <label className="chk">
            <input type="checkbox" checked={outline} onChange={() => set('outline', outline ? '0' : null)} /> outline overflow
          </label>
          <label className="chk">
            <input type="checkbox" checked={single} onChange={() => set('single', single ? null : '1')} /> one tier
          </label>
          {single && (
            <label>
              Tier
              <select value={tiers[0]} onChange={(e) => set('tier', e.target.value)}>
                {TIERS.map((t) => <option key={t} value={t}>{TIER_LABEL[t]}</option>)}
              </select>
            </label>
          )}
        </div>

        <div className="cells">
          <b>Cells held</b>
          {realAccess ? (
            <span className="small dim">your real permissions ({conn.permissions.length})</span>
          ) : (
            <>
              <button type="button" className="lnk" onClick={() => set('cells', null)}>all</button>
              <button type="button" className="lnk" onClick={() => set('cells', 'none')}>none</button>
            </>
          )}
          {allCells.map((c) => (
            <label key={c} className="chk mono">
              <input type="checkbox" checked={cells.includes(c)} disabled={realAccess} onChange={() => toggleCell(c)} /> {c}{' '}
              <span className="dim">{roleOf(c)}</span>
            </label>
          ))}
        </div>

        {configFields.length > 0 && (
          <div className="cells">
            <b>Config</b>
            {configFields.map(({ key, field }) => (
              <label key={key}>
                {field.label ?? key}
                {field.type === 'enum' ? (
                  <select value={String(config[key])} onChange={(e) => set(`cfg_${key}`, e.target.value)}>
                    {field.options.map((o) => <option key={o} value={o}>{o}</option>)}
                  </select>
                ) : field.type === 'boolean' ? (
                  <input
                    type="checkbox"
                    checked={config[key] === true}
                    onChange={(e) => set(`cfg_${key}`, e.target.checked ? 'true' : 'false')}
                  />
                ) : (
                  <input
                    key={String(config[key])}
                    type={field.type === 'number' ? 'number' : 'text'}
                    defaultValue={String(config[key] ?? '')}
                    onBlur={(e) => set(`cfg_${key}`, e.target.value)}
                  />
                )}
              </label>
            ))}
          </div>
        )}
      </section>

      <section className={single ? 'frames one' : 'frames'}>
        {tiers.map((t) => (
          <WidgetFrame
            key={`${t}:${frameKey}`}
            tier={t}
            setup={setup}
            single={single}
            focused={focus === t}
            onFocus={setFocus}
            onCalls={onCalls}
            register={register}
          />
        ))}
      </section>

      {!single && <NetworkTable frameLabel={TIER_LABEL[focus]} calls={callsByTier[focus] ?? []} />}
    </>
  );
}
