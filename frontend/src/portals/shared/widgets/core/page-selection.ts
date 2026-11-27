/**
 * Page-level selection shared by the widgets of one screen (SME WidgetPage "pick one to open it"):
 * a list widget calls context.select('orgId', id) and every widget on the screen reads
 * context.selection.orgId. URL-first (ui-std §7): the pick lives in `?org=` so a view can be
 * linked and survives reload.
 */
import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';

/** selection key → URL param */
const PARAM: Record<string, string> = { orgId: 'org' };

/** Per-subject detail state that belongs to the previous pick (drawer, paging, search). */
const RESET_ON_CHANGE = ['user', 'edit', 'q', 'page', 'status'];

export interface PageSelection {
  selection: Record<string, string>;
  onSelect: (key: string, value: string) => void;
}

export function usePageSelection(): PageSelection {
  const [params, setParams] = useSearchParams();
  const raw = params.toString();
  const selection = useMemo(() => {
    const p = new URLSearchParams(raw);
    const out: Record<string, string> = {};
    for (const [key, name] of Object.entries(PARAM)) {
      const v = p.get(name);
      if (v) out[key] = v;
    }
    return out;
  }, [raw]);

  const onSelect = useCallback(
    (key: string, value: string) =>
      setParams((p) => {
        const name = PARAM[key] ?? key;
        if (p.get(name) === value) return p;
        const n = new URLSearchParams(p);
        for (const k of RESET_ON_CHANGE) n.delete(k);
        if (value) n.set(name, value);
        else n.delete(name);
        return n;
      }),
    [setParams],
  );

  return { selection, onSelect };
}
