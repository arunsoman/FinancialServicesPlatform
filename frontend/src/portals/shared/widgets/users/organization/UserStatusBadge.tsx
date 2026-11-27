import { Chip, StatusBadge } from '../../../ui/atoms';
import type { UserStatus } from './model';

export interface UserStatusBadgeProps {
  status: UserStatus;
  /** false = approved but hasn't set a password from the IdP activation yet */
  activated?: boolean | null;
}

/** A staged user reads "Awaiting approval" (who acts next is a checker), every other state the shared map. */
export function UserStatusBadge({ status, activated }: UserStatusBadgeProps) {
  if (status === 'PENDING') return <Chip tone="warn">Awaiting approval</Chip>;
  if (status === 'ACTIVE' && activated === false) return <Chip tone="info">Awaiting activation</Chip>;
  return <StatusBadge status={status} />;
}
