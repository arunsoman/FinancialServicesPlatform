import { useWidgetRuntime, usePortData } from '../../core/runtime';
import {
  Chip,
  EmptyState,
  Panel,
  PageHeader,
  StatusBadge,
} from '../../../ui/atoms';
import type { UserPage } from './data';

export function OrgUsers() {
  const { port, context } = useWidgetRuntime();

  const page = usePortData<UserPage>(
    () =>
      port.request('org.users.list', {
        status: 'current',
        size: '50',
        sort: 'fullName,asc',
      }),
    [context.orgId],
  );

  if (page.state === 'loading') {
    return (
      <Panel className="p-4">
        <EmptyState title="Loading users…" />
      </Panel>
    );
  }
  if (page.state === 'forbidden') {
    return (
      <Panel className="p-4">
        <EmptyState
          title="Not allowed here"
          hint="Viewing organization users needs the user.manage permission."
        />
      </Panel>
    );
  }
  if (page.state === 'error') {
    return (
      <Panel className="p-4">
        <EmptyState
          title="Couldn’t load users"
          hint={page.problem?.detail}
        />
      </Panel>
    );
  }
  if (page.state === 'empty' || !page.data || page.data.items.length === 0) {
    return (
      <Panel className="p-4">
        <EmptyState
          title="No users found"
          hint="Users belonging to this organization will appear here."
        />
      </Panel>
    );
  }

  const users = page.data.items;

  return (
    <Panel className="p-4">
      <PageHeader
        title="Organization users"
        subtitle={`${users.length} user${users.length === 1 ? '' : 's'}`}
      />
      <ul className="flex flex-col divide-y divide-line">
        {users.map((u) => (
          <li key={u.id} className="flex flex-col gap-2 py-3 sm:flex-row sm:items-center sm:gap-3">
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-semibold">{u.fullName}</div>
              <div className="text-dim mono text-[11px]">{u.username}</div>
              {u.email ? (
                <div className="text-dim text-[11px] truncate">{u.email}</div>
              ) : null}
            </div>
            <div className="flex flex-wrap items-center gap-1.5">
              {u.roles.map((r) => (
                <Chip key={r.id} tone="neutral">
                  {r.name}
                </Chip>
              ))}
              {u.roles.length === 0 ? (
                <Chip tone="neutral">No roles</Chip>
              ) : null}
            </div>
            <StatusBadge status={u.status} />
          </li>
        ))}
      </ul>
    </Panel>
  );
}
