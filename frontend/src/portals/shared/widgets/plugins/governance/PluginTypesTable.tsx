import { Chip } from '../../../ui/atoms';
import type { PluginTypeRow } from './model';

export interface PluginTypesTableProps {
  types: PluginTypeRow[];
}

const TONE: Record<PluginTypeRow['status'], 'ok' | 'warn' | 'bad' | 'neutral'> = {
  REGISTERED: 'ok', PENDING_APPROVAL: 'warn', SUSPENDED: 'bad', DEACTIVATED: 'neutral',
};

export function PluginTypesTable({ types }: PluginTypesTableProps) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-separate border-spacing-0 text-sm">
        <thead>
          <tr className="text-dim text-left text-[11px] uppercase tracking-[.12em]">
            <th className="border-b border-line py-2 pr-3 font-bold">Plugin</th>
            <th className="border-b border-line py-2 pr-3 font-bold">Version</th>
            <th className="border-b border-line py-2 pr-3 font-bold">Status</th>
            <th className="border-b border-line py-2 pr-3 font-bold">Operations</th>
            <th className="border-b border-line py-2 font-bold">Permissions</th>
          </tr>
        </thead>
        <tbody>
          {types.map((t) => (
            <tr key={t.id}>
              <td className="border-b border-line py-2 pr-3">
                <div className="mono text-[12.5px] font-bold">{t.code}</div>
                <div className="text-dim text-[12px]">{t.name}</div>
              </td>
              <td className="mono border-b border-line py-2 pr-3 text-[12px]">{t.version}</td>
              <td className="border-b border-line py-2 pr-3">
                <Chip tone={TONE[t.status]}>{t.status === 'PENDING_APPROVAL' ? 'Awaiting approval' : t.status.toLowerCase()}</Chip>
              </td>
              <td className="border-b border-line py-2 pr-3 tabular-nums">{t.operationCount}</td>
              <td className="text-dim border-b border-line py-2 text-[12px]">{t.permissions.join(', ') || '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
