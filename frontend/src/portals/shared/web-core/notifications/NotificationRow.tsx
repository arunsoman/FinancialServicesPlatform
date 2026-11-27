import { useNavigate } from 'react-router-dom';
import { Alert, Button, Chip } from '../../ui/atoms';
import { opLabel } from '../../widgets/approvals/queue/model';
import { toProblem } from '../../widgets/kit/problem';
import { ApprovalFromNotification } from './ApprovalFromNotification';
import { SnoozeMenu } from './SnoozeMenu';
import { ago, markRead, untilLabel, useDismiss, useSnooze, useWake } from './model';
import type { NotificationItem } from './model';

export interface NotificationRowProps {
  item: NotificationItem;
  orgId: string;
  expanded: boolean;
  onToggle: () => void;
  onDecided: (approved: boolean) => void;
  onNavigate: () => void;
}

/**
 * One notification. An approval pointer expands into its checker card (decide here) and can be
 * snoozed — never dismissed, because that would hide four-eyes work. An informational notice
 * opens its link and can be dismissed. A snoozed row offers "Bring back now".
 */
export function NotificationRow({ item, orgId, expanded, onToggle, onDecided, onNavigate }: NotificationRowProps) {
  const navigate = useNavigate();
  const snooze = useSnooze();
  const wake = useWake();
  const dismiss = useDismiss();
  const snoozed = item.snoozedUntil !== null && Date.parse(item.snoozedUntil) > Date.now();
  const isApproval = item.actionable && item.resourceType === 'ApprovalRequest';
  const busy = snooze.isPending || wake.isPending || dismiss.isPending;
  const failure = snooze.error ?? wake.error ?? dismiss.error;

  const open = () => {
    if (item.unread) markRead(item.id);
    if (isApproval) {
      onToggle();
    } else if (item.link) {
      onNavigate();
      navigate(item.link);
    }
  };

  return (
    <li data-notification-id={item.id} className="rounded-2xl border border-line bg-panel p-3">
      <button type="button" className="flex w-full items-start gap-3 text-left" aria-expanded={isApproval ? expanded : undefined} onClick={open}>
        <span aria-hidden className={'mt-1.5 h-2 w-2 shrink-0 rounded-full ' + (item.unread ? 'bg-accent' : 'bg-transparent')} />
        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-extrabold">{item.title}</span>
            {isApproval && <Chip tone="warn">{opLabel(item.opType)}</Chip>}
          </span>
          <span className="text-dim mt-0.5 block text-[12.5px]">{item.body}</span>
          <span className="text-dim mt-1 block text-[11px]">
            {ago(item.createdAt)}
            {snoozed && <> · snoozed until {untilLabel(item.snoozedUntil)}</>}
          </span>
        </span>
        {isApproval && <span aria-hidden className="text-dim text-xs">{expanded ? '▴' : '▾'}</span>}
      </button>

      {failure && <div className="mt-2"><Alert kind="bad">{toProblem(failure).message}</Alert></div>}

      {expanded && isApproval && (
        <ApprovalFromNotification approvalId={item.resourceId} orgId={orgId} onDecided={onDecided} />
      )}

      <div className="mt-2 flex flex-wrap items-center justify-end gap-2">
        {snoozed ? (
          <Button variant="ghost" className="px-3 py-1 text-xs" disabled={busy} onClick={() => wake.mutate(item.id)}>
            Bring back now
          </Button>
        ) : (
          <SnoozeMenu busy={busy} onSnooze={(until) => snooze.mutate({ id: item.id, until })} />
        )}
        {!item.actionable && (
          <Button variant="ghost" className="px-3 py-1 text-xs" disabled={busy} onClick={() => dismiss.mutate(item.id)}>
            Dismiss
          </Button>
        )}
        {isApproval && !expanded && (
          <Button variant="primary" className="px-3 py-1 text-xs" onClick={open}>Review</Button>
        )}
      </div>
    </li>
  );
}
