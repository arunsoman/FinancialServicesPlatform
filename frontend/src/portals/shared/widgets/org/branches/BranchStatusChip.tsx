import { Chip } from '../../../ui/atoms';
import { statusView } from './model';
import type { BranchRow } from './model';

export interface BranchStatusChipProps {
  branch: Pick<BranchRow, 'status' | 'pending'>;
}

/** Status in words (never colour alone): Active · Awaiting approval · Rename/Deletion awaiting approval · Rejected · Deleted. */
export function BranchStatusChip({ branch }: BranchStatusChipProps) {
  const v = statusView(branch);
  return <Chip tone={v.tone}>{v.label}</Chip>;
}
