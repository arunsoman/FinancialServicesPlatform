/** permissions.catalogue — every permission the platform knows, grouped by component, with which ones YOU hold. */
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { useWidgetRuntime } from '../../core/runtime';
import { Chip, Input, PageHeader, Panel } from '../../../ui/atoms';
import { StatePanel } from '../../kit/StatePanel';
import { isForbidden, toProblem } from '../../kit/problem';

interface PermissionDef {
  key: string;
  title: string;
  risk: 'READ' | 'WRITE' | 'FOUR_EYES';
  component: string;
}

const RISK_TONE: Record<PermissionDef['risk'], 'neutral' | 'info' | 'warn'> = { READ: 'neutral', WRITE: 'info', FOUR_EYES: 'warn' };

export function PermissionsCatalogue() {
  const { port, context } = useWidgetRuntime();
  const [params, setParams] = useSearchParams();
  const q = (params.get('q') ?? '').trim().toLowerCase();
  const list = useQuery({
    queryKey: ['rbac', 'catalog'],
    queryFn: () => port.request<PermissionDef[]>('permissions.catalogue.list'),
    staleTime: 10 * 60_000,
  });
  if (list.isPending) return <StatePanel kind="loading" title="Loading permissions" />;
  if (list.isError) {
    return isForbidden(list.error)
      ? <StatePanel kind="forbidden" title="Not allowed here" />
      : <StatePanel kind="error" title="Couldn’t load permissions" hint={toProblem(list.error).message} onRetry={() => void list.refetch()} />;
  }
  const held = new Set(context.permissions);
  const rows = (list.data ?? []).filter((p) => !q || p.key.includes(q) || p.title.toLowerCase().includes(q) || p.component.includes(q));
  const groups = new Map<string, PermissionDef[]>();
  for (const p of [...rows].sort((a, b) => a.key.localeCompare(b.key))) groups.set(p.component, [...(groups.get(p.component) ?? []), p]);

  return (
    <Panel className="p-4">
      <PageHeader title="All permissions" subtitle={`${list.data?.length ?? 0} keys · you hold ${held.size}`}
        actions={<Input aria-label="Search permissions" placeholder="Search…" value={params.get('q') ?? ''}
          onChange={(e) => setParams((p) => { const n = new URLSearchParams(p); if (e.target.value) n.set('q', e.target.value); else n.delete('q'); return n; }, { replace: true })} />} />
      {rows.length === 0 ? <StatePanel kind="empty" title="No permission matches" /> : (
        <div className="grid gap-4 md:grid-cols-2">
          {[...groups.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([component, defs]) => (
            <section key={component} className="rounded-xl border border-line p-3">
              <h3 className="fsp-eyebrow mb-2">{component}</h3>
              <ul className="grid gap-1.5">
                {defs.map((d) => (
                  <li key={d.key} className="flex items-start justify-between gap-2">
                    <span className="min-w-0">
                      <span className="mono block text-[12px] font-semibold">{d.key}</span>
                      <span className="text-dim block text-[12px]">{d.title}</span>
                    </span>
                    <span className="flex shrink-0 gap-1">
                      <Chip tone={RISK_TONE[d.risk]}>{d.risk === 'FOUR_EYES' ? 'four-eyes' : d.risk.toLowerCase()}</Chip>
                      {held.has(d.key) && <Chip tone="ok">held</Chip>}
                    </span>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}
    </Panel>
  );
}
