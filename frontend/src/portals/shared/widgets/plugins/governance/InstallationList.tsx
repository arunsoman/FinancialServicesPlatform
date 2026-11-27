import { Chip } from '../../../ui/atoms';
import type { InstallationRow } from './model';

export interface InstallationListProps {
  installations: InstallationRow[];
}

/** Installations of one organization with their readiness gates (the gateway's PLUGIN_NOT_READY inputs). */
export function InstallationList({ installations }: InstallationListProps) {
  return (
    <ul className="grid gap-2">
      {installations.map((i) => (
        <li key={i.id} className="rounded-xl border border-line p-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="mono text-[12.5px] font-bold">{i.pluginCode} <span className="text-dim">{i.pluginVersion}</span></span>
            <div className="flex gap-1.5">
              <Chip tone={i.status === 'ACTIVE' ? 'ok' : i.status === 'PENDING_APPROVAL' ? 'warn' : 'info'}>
                {i.status === 'PENDING_APPROVAL' ? 'Awaiting approval' : i.status.toLowerCase()}
              </Chip>
              <Chip tone={i.ready ? 'ok' : 'warn'}>{i.ready ? 'Ready' : 'Not ready'}</Chip>
            </div>
          </div>
          {i.gates.length > 0 && (
            <div className="mt-2 flex flex-wrap gap-1.5">
              {i.gates.map((g) => (
                <Chip key={g.gate} tone={g.status === 'ACTIVE' ? 'ok' : g.status === 'FAILED' ? 'bad' : 'neutral'}>
                  {g.gate.replace(/_/g, ' ').toLowerCase()}
                </Chip>
              ))}
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}
