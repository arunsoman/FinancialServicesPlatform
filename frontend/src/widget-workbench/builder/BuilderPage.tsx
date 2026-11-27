import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { readProblem } from '../../portals/shared/web-core/api';
import { TIERS } from '../../portals/shared/widgets/core/grid';
import type { Tier } from '../../portals/shared/widgets/core/grid';
import type { WidgetDef } from '../../portals/shared/widgets/core/manifest';
import { WIDGETS } from '../../portals/shared/widgets/registry';
import { useConnection } from '../connection';
import { BUILDER_PORTALS, TIER_LABEL, worksIn } from '../model';
import { ArmButton } from './ArmButton';
import { DesignCanvas } from './DesignCanvas';
import { FindingList } from './FindingList';
import {
  loadLocalDraft, move, parseLayout, placeWidget, placedSpan, saveLocalDraft, screenResources, screenRoute, seedLayout,
  uniqueId, validateLayout,
} from './layout';
import type { LayoutMenu, LayoutScreen, PortalLayout } from './layout';
import { MenuInspector } from './MenuInspector';
import { MenuTree } from './MenuTree';
import { PlacedInspector } from './PlacedInspector';
import { PreviewButtons } from './PreviewButtons';
import { ScreenInspector } from './ScreenInspector';
import type { Sel } from './selection';
import { useSaveDraft, useServerLayouts } from './server-layouts';
import { autosaveLabel, newestServerLayout, sameLayout } from './server-sync';
import type { AutosaveState } from './server-sync';
import { WidgetPalette } from './WidgetPalette';

const DEFS: ReadonlyMap<string, WidgetDef> = new Map(WIDGETS.map((w) => [w.id, w]));

function firstScreen(l: PortalLayout): Sel {
  const m = l.menus.findIndex((x) => x.screens.length);
  return m >= 0 ? { kind: 'screen', m, s: 0 } : null;
}

/**
 * The portal builder (SME builder-page). A developer:
 *  1. builds the portal's menus and submenus;
 *  2. opens a submenu's screen and drops widgets from the palette (this build's registry);
 *  3. sizes them per tier, phone first, and checks the screen at every tier;
 *  4. previews the real portal at desktop / tablet / mobile size (new windows, live);
 *  5. ships it: every edit autosaves to the server draft, and the menu-publisher skill
 *     exports it into the repo seed that every environment applies (docs/portal-menus.md).
 * The editor's copy stays in this browser (localStorage) between visits; with no copy here,
 * the builder opens the server's newest layout (never the code's default menus over it).
 */
export function BuilderPage() {
  const conn = useConnection();
  const [q, setQ] = useSearchParams();
  const portal = BUILDER_PORTALS.find((p) => p.key === q.get('portal')) ?? BUILDER_PORTALS[0];
  const tier: Tier = TIERS.includes(q.get('tier') as Tier) ? (q.get('tier') as Tier) : 'xs';
  const setParam = (key: string, value: string | null) =>
    setQ(
      (p) => {
        const n = new URLSearchParams(p);
        if (value === null) n.delete(key);
        else n.set(key, value);
        return n;
      },
      { replace: true },
    );

  const [layout, setLayout] = useState<PortalLayout>(() => loadLocalDraft(portal.key) ?? seedLayout(portal, DEFS));
  const [sel, setSel] = useState<Sel>(() => firstScreen(layout));
  // no editor copy in this browser → the layout came from the code's default menus; the server's
  // newest layout replaces it once it loads, unless the developer has already edited
  const [fromCode, setFromCode] = useState(() => loadLocalDraft(portal.key) === null);
  // the layout as the server last had it: autosave runs only when the editor differs from it
  const [savedJson, setSavedJson] = useState(() => JSON.stringify(layout));
  // switching portal loads that portal's editor copy (adjust state during render, not in an effect)
  const [shownPortal, setShownPortal] = useState(portal.key);
  if (shownPortal !== portal.key) {
    const local = loadLocalDraft(portal.key);
    const next = local ?? seedLayout(portal, DEFS);
    setShownPortal(portal.key);
    setLayout(next);
    setSel(firstScreen(next));
    setFromCode(local === null);
    setSavedJson(JSON.stringify(next));
  }
  // never persist the code's untouched default menus: a later visit would take them for the
  // developer's own work and autosave them over the server's layout
  const untouchedDefault = fromCode && JSON.stringify(layout) === savedJson;
  useEffect(() => {
    if (!untouchedDefault) saveLocalDraft(layout);
  }, [layout, untouchedDefault]);

  const canSave = conn.status === 'connected' && conn.canBuild;
  const serverLayouts = useServerLayouts(portal.key, canSave);
  const serverNewest = newestServerLayout(portal.key, serverLayouts.draft.data, serverLayouts.published.data);
  if (fromCode && serverLayouts.draft.isFetched && serverLayouts.published.isFetched) {
    setFromCode(false);
    if (serverNewest && JSON.stringify(layout) === savedJson) {
      setLayout(serverNewest);
      setSel(firstScreen(serverNewest));
      setSavedJson(JSON.stringify(serverNewest));
    }
  }

  // the menus that ship today: core's PUBLISHED layout (every environment loads it from the repo seed)
  const shipped = serverLayouts.published.data ? parseLayout(serverLayouts.published.data.layoutJson, portal.key) : null;

  const palette = useMemo(() => WIDGETS.filter((d) => worksIn(d, portal.context)), [portal.context]);
  const findings = useMemo(() => validateLayout(layout, DEFS, portal), [layout, portal]);
  const errors = findings.filter((f) => f.level === 'error').length;
  const warnings = findings.length - errors;

  // ------------------------------------------------- autosave to the server draft (debounced)
  const save = useSaveDraft(portal.key);
  const [saveFail, setSaveFail] = useState<{ json: string; text: string } | null>(null);
  const json = JSON.stringify(layout);
  const dirty = json !== savedJson;
  const blocked = !canSave || errors > 0 || fromCode;
  useEffect(() => {
    if (blocked || !dirty) return undefined;
    const t = setTimeout(() => {
      save.mutate(layout, {
        onSuccess: () => {
          setSavedJson(json);
          setSaveFail(null);
        },
        onError: (e) => setSaveFail({ json, text: readProblem(e).detail }),
      });
    }, 1200);
    return () => clearTimeout(t);
    // `save` is a fresh object each render; the layout JSON is the trigger
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [json, blocked, dirty]);
  const autosave: AutosaveState = !canSave
    ? { kind: 'off', why: 'Sign in to save to the server' }
    : errors > 0
      ? { kind: 'off', why: 'Not saved: fix the errors first' }
      : save.isPending
        ? { kind: 'saving' }
        : saveFail?.json === json
          ? { kind: 'failed', text: saveFail.text }
          : dirty
            ? { kind: 'pending' }
            : { kind: 'saved' };
  // this browser's copy is not what the server holds (an older visit, another machine's edits):
  // say so before the next edit autosaves over it
  const serverDiffers = canSave && !fromCode && !dirty && !!serverNewest && !sameLayout(layout, serverNewest);

  const mi = sel ? sel.m : -1;
  const si = sel && sel.kind !== 'menu' ? sel.s : -1;
  const menu: LayoutMenu | null = layout.menus[mi] ?? null;
  const screen: LayoutScreen | null = menu?.screens[si] ?? null;
  const selWidget = sel?.kind === 'widget' ? sel.id : null;
  const placed = screen?.placedWidgets.find((p) => p.id === selWidget) ?? null;
  const placedDef = placed ? DEFS.get(placed.widgetId) ?? null : null;

  // ---------------------------------------------------------------- edits (immutable)
  const edit = (fn: (l: PortalLayout) => void) =>
    setLayout((cur) => {
      const next = structuredClone(cur);
      fn(next);
      return next;
    });
  const editScreen = (fn: (s: LayoutScreen) => void) => {
    if (mi < 0 || si < 0) return;
    edit((l) => fn(l.menus[mi].screens[si]));
  };

  const addMenu = () => {
    edit((l) => {
      l.menus.push({ id: uniqueId('new-menu', l.menus.map((m) => m.id)), label: 'New menu', screens: [] });
    });
    setSel({ kind: 'menu', m: layout.menus.length });
  };
  const addScreen = (m: number) => {
    edit((l) => {
      const ms = l.menus[m];
      ms.screens.push({ id: uniqueId('new-screen', ms.screens.map((s) => s.id)), label: 'New screen', placedWidgets: [] });
    });
    setSel({ kind: 'screen', m, s: layout.menus[m].screens.length });
  };
  const moveSel = (by: number) => {
    if (!sel) return;
    if (sel.kind === 'menu') {
      const to = Math.max(0, Math.min(layout.menus.length - 1, sel.m + by));
      edit((l) => move(l.menus, sel.m, to));
      setSel({ kind: 'menu', m: to });
    } else if (sel.kind === 'screen') {
      const to = Math.max(0, Math.min(layout.menus[sel.m].screens.length - 1, sel.s + by));
      edit((l) => move(l.menus[sel.m].screens, sel.s, to));
      setSel({ kind: 'screen', m: sel.m, s: to });
    }
  };
  const deleteSel = () => {
    if (!sel) return;
    edit((l) => {
      if (sel.kind === 'menu') l.menus.splice(sel.m, 1);
      else if (sel.kind === 'screen') l.menus[sel.m].screens.splice(sel.s, 1);
    });
    setSel(null);
  };

  const addWidget = (widgetId: string, at: number) => {
    const def = DEFS.get(widgetId);
    if (!def) return;
    const it = placeWidget(def);
    editScreen((s) => s.placedWidgets.splice(at, 0, it));
    setSel({ kind: 'widget', m: mi, s: si, id: it.id });
  };
  const removeWidget = (id: string) => {
    editScreen((s) => {
      s.placedWidgets = s.placedWidgets.filter((p) => p.id !== id);
    });
    setSel({ kind: 'screen', m: mi, s: si });
  };
  const resizeWidget = (id: string, by: number) =>
    editScreen((s) => {
      const it = s.placedWidgets.find((p) => p.id === id);
      const d = it ? DEFS.get(it.widgetId) : undefined;
      const z = d?.size[tier];
      const now = it && d ? placedSpan(d, it, tier) : null;
      if (!it || !z || z.kind !== 'span' || now === null) return;
      it.span = { ...it.span, [tier]: Math.max(z.min, Math.min(z.max, now + by)) };
    });

  const portalLabel = portal.label;

  return (
    <>
      <section className="bd-top">
        <h1>Portal builder</h1>
        <label>
          Portal
          <select value={portal.key} onChange={(e) => setParam('portal', e.target.value)}>
            {BUILDER_PORTALS.map((p) => <option key={p.key} value={p.key}>{p.label}</option>)}
          </select>
        </label>
        <span className="grow" />
        <span className={errors ? 'badge bad' : 'badge ok'}>{errors} error(s) · {warnings} warning(s)</span>
        <span className={autosave.kind === 'failed' ? 'bad small' : 'dim small'} data-state={`autosave-${autosave.kind}`}>
          {autosaveLabel(autosave)}
        </span>
        <PreviewButtons portalKey={portal.key} />
        <ArmButton
          className="btn ghost"
          label="Discard my changes"
          armedLabel="Click again: back to the shipped menus"
          disabled={!shipped}
          title={shipped ? 'Go back to the menus that ship today' : canSave ? 'Nothing has shipped for this portal yet' : 'Sign in to load the shipped menus'}
          onConfirm={() => {
            if (!shipped) return;
            setLayout(shipped); // autosave then makes the shipped menus the server draft again
            setSel(firstScreen(shipped));
          }}
        />
      </section>
      <p className="dim small" data-state="ship-hint">
        Every change saves automatically. To ship the menus, run the menu publisher (<span className="mono">make menus-publish</span>)
        — CI/CD deploys them like any other code.
      </p>

      {serverDiffers && serverNewest && (
        <p className="bad small" data-state="server-differs">
          This browser’s copy of the {portalLabel} menus differs from the server’s newest layout — your next edit
          would replace the server’s.{' '}
          <button
            type="button"
            className="lnk"
            onClick={() => {
              setLayout(serverNewest);
              setSel(firstScreen(serverNewest));
              setSavedJson(JSON.stringify(serverNewest));
            }}
          >
            Use the server’s layout
          </button>
        </p>
      )}

      <div className="bd">
        <aside className="bd-left">
          <MenuTree
            layout={layout}
            sel={sel}
            onSelect={(s) => {
              setSel(s);
            }}
            onAddMenu={addMenu}
            onAddScreen={addScreen}
            onMoveMenu={(from, to) => {
              edit((l) => move(l.menus, from, to));
              setSel({ kind: 'menu', m: to });
            }}
            onMoveScreen={(from, to) => {
              edit((l) => {
                const [x] = l.menus[from.m].screens.splice(from.s, 1);
                const dest = l.menus[to.m].screens;
                const at = from.m === to.m && from.s < to.s ? to.s - 1 : to.s;
                dest.splice(Math.min(at, dest.length), 0, x);
              });
              setSel(null);
            }}
          />
          <WidgetPalette widgets={palette} portalLabel={portalLabel} />
        </aside>

        <main className="bd-mid">
          <nav className="tabs" aria-label="Screen size">
            <span className="grow" />
            {TIERS.map((t) => (
                <button
                  key={t}
                  type="button"
                  className={tier === t ? 'btn on' : 'btn'}
                  onClick={() => setParam('tier', t === 'xs' ? null : t)}
                >
                  {TIER_LABEL[t]}
                </button>
              ))}
          </nav>

          {menu && screen ? (
              <>
                <div className="crumb">
                  <span>{menu.label} › {screen.label}</span>
                  <span className="mono dim small">{screenRoute(menu, screen)}</span>
                </div>
                <DesignCanvas
                  context={portal.context}
                  tier={tier}
                  items={screen.placedWidgets}
                  defs={DEFS}
                  selected={selWidget}
                  onSelect={(id) => setSel({ kind: 'widget', m: mi, s: si, id })}
                  onRemove={removeWidget}
                  onResize={resizeWidget}
                  onReorder={(from, to) => editScreen((s) => move(s.placedWidgets, from, to))}
                  onAdd={addWidget}
                />
              </>
            ) : (
              <div className="empty">Pick a submenu on the left to open its screen, or add one with “+ sub”.</div>
            )}
        </main>

        <aside className="bd-right">
          {sel?.kind === 'menu' && menu && (
            <MenuInspector
              menu={menu}
              onChange={(patch) => edit((l) => Object.assign(l.menus[mi], patch))}
              onMove={moveSel}
              onDelete={deleteSel}
            />
          )}
          {sel?.kind === 'screen' && menu && screen && (
            <ScreenInspector
              screen={screen}
              route={screenRoute(menu, screen)}
              resources={screenResources(screen, DEFS)}
              onChange={(patch) => edit((l) => Object.assign(l.menus[mi].screens[si], patch))}
              onMove={moveSel}
              onDelete={deleteSel}
            />
          )}
          {sel?.kind === 'widget' && placed && placedDef && (
            <PlacedInspector
              def={placedDef}
              placed={placed}
              onSpan={(t, v) =>
                editScreen((s) => {
                  const it = s.placedWidgets.find((p) => p.id === placed.id);
                  if (it) it.span = { ...it.span, [t]: v };
                })
              }
              onConfig={(key, v) =>
                editScreen((s) => {
                  const it = s.placedWidgets.find((p) => p.id === placed.id);
                  if (it) it.config = { ...it.config, [key]: v };
                })
              }
              onRemove={() => removeWidget(placed.id)}
            />
          )}
          {!sel && <div className="dim small">Select a menu, a submenu or a placed widget.</div>}
          <FindingList findings={findings} />
        </aside>
      </div>
    </>
  );
}
