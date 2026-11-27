/** overview.tiles — the landing tiles: your rights, your organization, who you are. */
import { useSession } from '../../../web-core/session-core';
import { Chip, KvPair, Panel, StatusBadge } from '../../../ui/atoms';

export function OverviewTiles() {
  const { user, tenant, permissions } = useSession();
  return (
    <div className="grid gap-4 md:grid-cols-3">
      <Panel className="p-4">
        <div className="fsp-eyebrow mb-2">Your rights</div>
        <div className="text-3xl font-extrabold" style={{ color: 'var(--portal-accent)' }}>{permissions.length}</div>
        <div className="text-dim text-xs">permission keys held</div>
      </Panel>
      <Panel className="p-4">
        <div className="fsp-eyebrow mb-2">Organization</div>
        {tenant ? (
          <div className="flex flex-col gap-2">
            <KvPair label="Legal name">{tenant.legalName ?? '—'}</KvPair>
            <div className="flex gap-2">
              <Chip tone="accent">{tenant.subType ?? tenant.type}</Chip>
              <StatusBadge status={tenant.status} />
            </div>
          </div>
        ) : (
          <div className="text-dim text-sm">Platform root organization</div>
        )}
      </Panel>
      <Panel className="p-4">
        <div className="fsp-eyebrow mb-2">Signed in as</div>
        <KvPair label="Name">{user?.fullName}</KvPair>
        <div className="text-dim mono mt-1">{user?.username}</div>
        <div className="mt-2 flex flex-wrap gap-1.5">
          {(user?.roles ?? []).map((r) => <Chip key={r} tone="info">{r}</Chip>)}
        </div>
      </Panel>
    </div>
  );
}
