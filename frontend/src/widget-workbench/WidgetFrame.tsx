import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { COLS } from '../portals/shared/widgets/core/grid';
import type { Tier } from '../portals/shared/widgets/core/grid';
import type { WidgetDef } from '../portals/shared/widgets/core/manifest';
import { requiresMet } from '../portals/shared/widgets/core/manifest';
import { WidgetHost } from '../portals/shared/widgets/core/host';
import type { CallRecord } from '../portals/shared/widgets/core/ports';
import { FIXTURES } from '../portals/shared/widgets/fixtures';
import type { Scenario } from '../portals/shared/widgets/fixtures';
import { FrameRouter } from './FrameRouter';
import { measure } from './measure';
import type { Geometry } from './measure';
import { TIER_LABEL, TIER_TEST_WIDTH } from './model';

export interface FrameSetup {
  def: WidgetDef;
  portal: string;
  orgId: string | null;
  cells: readonly string[];
  scenario: Scenario;
  latency: number;
  live: boolean;
  config: Record<string, unknown>;
  grid: boolean;
  outline: boolean;
}

export interface WidgetFrameProps {
  tier: Tier;
  setup: FrameSetup;
  single: boolean;
  focused: boolean;
  onFocus: (t: Tier) => void;
  onCalls: (t: Tier, calls: CallRecord[]) => void;
  /** e2e hook: the page registers each frame's measure/state reader */
  register: (t: Tier, api: { measure: () => Geometry | null; state: () => string } | null) => void;
}

type FrameState = 'loading' | 'ready' | 'empty' | 'error' | 'forbidden' | 'hidden';

function stateOf(calls: readonly CallRecord[], settled: boolean): FrameState {
  if (calls.some((c) => c.status === 'forbidden')) return 'forbidden';
  if (calls.some((c) => c.status === 'error')) return 'error';
  if (calls.length && calls.every((c) => c.status === 'empty')) return 'empty';
  if (calls.length || settled) return 'ready';
  return 'loading';
}

/**
 * One grid tier (SME wb-frame): a content area exactly TIER_TEST_WIDTH wide, the tier's
 * column grid, and the widget in a cell spanning its default span for that tier. Each
 * frame has its own access, port, query cache and URL, so tiers never share state.
 * The parent re-keys the frame on any setup change → a clean rebuild.
 */
export function WidgetFrame({ tier, setup, single, focused, onFocus, onCalls, register }: WidgetFrameProps) {
  const width = TIER_TEST_WIDTH[tier];
  const cols = COLS[tier];
  const size = setup.def.size[tier];
  const span = size.kind === 'span' ? size.default : null;
  const permitted = requiresMet(setup.def, setup.cells);

  const [queryClient] = useState(
    () => new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 30_000 }, mutations: { retry: 0 } } }),
  );
  const [calls, setCalls] = useState<CallRecord[]>([]);
  const [settled, setSettled] = useState(false);
  const [geometry, setGeometry] = useState<Geometry | null>(null);
  const cellRef = useRef<HTMLDivElement>(null);

  const record = useCallback((c: CallRecord) => setCalls((cs) => [...cs, c]), []);
  useEffect(() => onCalls(tier, calls), [tier, calls, onCalls]);

  // a widget that makes no calls is "ready" once it had time to start
  useEffect(() => {
    const t = setTimeout(() => setSettled(true), 400 + setup.latency);
    return () => clearTimeout(t);
  }, [setup.latency]);

  const state: FrameState = span === null ? 'hidden' : !permitted ? 'forbidden' : stateOf(calls, settled);

  const measureNow = useCallback(
    (mark = false) => (cellRef.current ? measure(cellRef.current, tier, mark) : null),
    [tier],
  );

  // measure once the widget is quiet
  useEffect(() => {
    if (state === 'loading' || span === null) return undefined;
    const t = setTimeout(() => setGeometry(measureNow(setup.outline)), 250);
    return () => clearTimeout(t);
  }, [state, calls.length, setup.outline, span, measureNow]);

  useEffect(() => {
    register(tier, { measure: () => measureNow(false), state: () => state });
    return () => register(tier, null);
  }, [register, tier, measureNow, state]);

  const pageStyle = useMemo(() => ({ width, ['--cols' as string]: cols }) as CSSProperties, [width, cols]);
  const className = ['frame', focused && !single ? 'focus' : '', single ? 'single' : ''].filter(Boolean).join(' ');

  return (
    <div className={className} data-tier={tier} onClick={() => onFocus(tier)}>
      <header className="fh">
        <b>{TIER_LABEL[tier]}</b>
        <span className="dim">
          {width}px · {tier} · {span === null ? 'hidden' : `span ${span}/${cols}`}
        </span>
        <span className="grow" />
        <span className={`st st-${state}`} data-frame-state aria-live="polite"
          title={state === 'forbidden' ? 'Cells held do not satisfy the widget\'s requires, or a source returned 403' : `Frame state: ${state}`}>
          {state}
        </span>
      </header>
      <div className="page" style={pageStyle}>
        {setup.grid && (
          <div className="cols" aria-hidden="true">
            {Array.from({ length: cols }, (_, i) => <i key={i} />)}
          </div>
        )}
        {span === null ? (
          <div className="hiddentier">Hidden on this tier: {size.kind === 'hidden' ? size.reason : ''}</div>
        ) : (
          <div className="cell" ref={cellRef} style={{ gridColumn: `span ${span}` }} data-cell>
            <QueryClientProvider client={queryClient}>
              <FrameRouter>
                <WidgetHost
                  def={setup.def}
                  mode={setup.live ? 'live' : 'fixture'}
                  fixtures={FIXTURES}
                  scenario={setup.scenario}
                  latencyMs={setup.latency}
                  cells={setup.cells}
                  portal={setup.portal}
                  orgId={setup.orgId}
                  config={setup.config}
                  onCall={record}
                  forbidden={
                    <div className="dim small">
                      Not shown: the cells held do not satisfy <span className="mono">requires</span>.
                    </div>
                  }
                />
              </FrameRouter>
            </QueryClientProvider>
          </div>
        )}
      </div>
      {geometry && (
        <footer className="geo">
          {!geometry.overflowX.length && !geometry.outsideCell.length && (
            <span className="ok">✓ no overflow · nothing outside the cell</span>
          )}
          {geometry.overflowX.map((x, i) => <div key={`o${i}`} className="bad">overflow-x: {x}</div>)}
          {geometry.outsideCell.map((x, i) => <div key={`c${i}`} className="bad">outside cell: {x}</div>)}
          {geometry.smallTargets.length > 0 && (
            <div className="warn">{geometry.smallTargets.length} tap target(s) under 44px</div>
          )}
        </footer>
      )}
    </div>
  );
}
