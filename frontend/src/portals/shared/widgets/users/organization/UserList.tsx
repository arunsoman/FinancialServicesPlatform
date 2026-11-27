import { clsx } from 'clsx';
import { RoleChips } from './RoleChips';
import { UserStatusBadge } from './UserStatusBadge';
import { UserIdentity } from './UserIdentity';
import { relativeTime } from './model';
import type { UserRow } from './model';

export interface UserListProps {
  users: UserRow[];
  /** compact = card list (narrow containers); otherwise a table. */
  compact: boolean;
  meId: string | null;
  selectedId: string | null;
  onOpen: (user: UserRow) => void;
}

/** Users as a scannable table (wide) or tappable cards (narrow). Rows open the detail drawer. */
export function UserList({ users, compact, meId, selectedId, onOpen }: UserListProps) {
  if (compact) {
    return (
      <ul className="grid gap-2">
        {users.map((u) => (
          <li key={u.id}>
            <button
              type="button"
              onClick={() => onOpen(u)}
              aria-current={selectedId === u.id}
              className={clsx(
                'w-full rounded-xl border bg-sunken p-3 text-left transition-colors hover:border-line-strong',
                selectedId === u.id ? 'border-line-strong' : 'border-line',
              )}
            >
              <span className="flex items-start justify-between gap-2">
                <UserIdentity user={u} me={u.id === meId} />
                <UserStatusBadge status={u.status} activated={u.activated} />
              </span>
              <span className="mt-2 block">
                <RoleChips roles={u.roles} />
              </span>
            </button>
          </li>
        ))}
      </ul>
    );
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full border-separate border-spacing-0 text-sm">
        <thead>
          <tr className="text-dim text-left text-[10.5px] font-bold uppercase tracking-[.14em]">
            <th scope="col" className="border-b border-line px-3 py-2 font-bold">User</th>
            <th scope="col" className="border-b border-line px-3 py-2 font-bold">Email</th>
            <th scope="col" className="border-b border-line px-3 py-2 font-bold">Roles</th>
            <th scope="col" className="border-b border-line px-3 py-2 font-bold">Status</th>
            <th scope="col" className="border-b border-line px-3 py-2 text-right font-bold">Updated</th>
          </tr>
        </thead>
        <tbody>
          {users.map((u) => (
            <tr
              key={u.id}
              tabIndex={0}
              aria-selected={selectedId === u.id}
              onClick={() => onOpen(u)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  onOpen(u);
                }
              }}
              className={clsx(
                'cursor-pointer outline-none transition-colors hover:bg-raised focus-visible:bg-raised',
                selectedId === u.id && 'bg-raised',
              )}
            >
              <td className="border-b border-line px-3 py-2.5">
                <UserIdentity user={u} me={u.id === meId} />
              </td>
              <td className="text-dim max-w-[220px] truncate border-b border-line px-3 py-2.5">{u.email ?? '—'}</td>
              <td className="border-b border-line px-3 py-2.5">
                <RoleChips roles={u.roles} />
              </td>
              <td className="border-b border-line px-3 py-2.5">
                <UserStatusBadge status={u.status} activated={u.activated} />
              </td>
              <td className="text-dim whitespace-nowrap border-b border-line px-3 py-2.5 text-right text-xs tabular-nums">
                {relativeTime(u.updatedAt)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
