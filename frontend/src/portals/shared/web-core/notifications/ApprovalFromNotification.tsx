import { ApprovalCard } from '../../widgets/approvals/queue/ApprovalCard';
import { StatePanel } from '../../widgets/kit/StatePanel';
import { isForbidden, toProblem } from '../../widgets/kit/problem';
import { useApproval, useDecideFromNotification } from './model';

export interface ApprovalFromNotificationProps {
  approvalId: string;
  orgId: string;
  onDecided: (approved: boolean) => void;
}

/**
 * The checker surface for one request, opened from its notification: the SAME card as the
 * Approvals queue (read-only proposal, maker identity, server-decided canDecide/blockedReason,
 * reason required to reject — ui-std §5.2). The maker of the request is never offered Approve.
 */
export function ApprovalFromNotification({ approvalId, orgId, onDecided }: ApprovalFromNotificationProps) {
  const approval = useApproval(approvalId);
  const decide = useDecideFromNotification(orgId);

  if (approval.isPending) return <StatePanel kind="loading" title="Loading the request" />;
  if (approval.isError) {
    return isForbidden(approval.error)
      ? <StatePanel kind="forbidden" title="Not allowed" hint="You no longer hold a checker permission for this request." />
      : <StatePanel kind="error" title="Couldn’t load the request" hint={toProblem(approval.error).message}
          onRetry={() => void approval.refetch()} />;
  }

  return (
    <ul className="mt-2">
      <ApprovalCard
        row={approval.data}
        busy={decide.isPending}
        problem={decide.isError ? toProblem(decide.error).message : null}
        onResetProblem={() => decide.reset()}
        onDecide={(approve, reason) =>
          decide.mutate({ approvalId, approve, reason }, { onSuccess: () => onDecided(approve) })}
      />
    </ul>
  );
}
