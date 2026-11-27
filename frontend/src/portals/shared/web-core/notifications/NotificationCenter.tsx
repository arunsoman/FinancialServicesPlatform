import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { Button } from '../../ui/atoms';
import { Drawer } from '../../widgets/kit/Drawer';
import { NoticeBar } from '../../widgets/kit/Notice';
import type { Notice } from '../../widgets/kit/Notice';
import { StatePanel } from '../../widgets/kit/StatePanel';
import { isForbidden, toProblem } from '../../widgets/kit/problem';
import { NotificationRow } from './NotificationRow';
import { PAGE_SIZE, useInbox } from './model';
import type { InboxState } from './model';

export interface NotificationCenterProps {
  orgId: string;
  onClose: () => void;
}

/**
 * The person's notification centre: "Needs action" (not acted upon, awake) and "Snoozed".
 * Both lists are server-paged; deciding an approval removes it for every checker.
 */
export function NotificationCenter({ orgId, onClose }: NotificationCenterProps) {
  const [state, setState] = useState<InboxState>('pending');
  const [size, setSize] = useState(PAGE_SIZE);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const dismissNotice = useCallback(() => setNotice(null), []);
  const inbox = useInbox(state, size, true);

  // any link followed from inside (e.g. "Open the user" on an approval card) closes the sheet
  const location = useLocation();
  const opened = useRef(location.key);
  useEffect(() => {
    if (location.key !== opened.current) onClose();
  }, [location.key, onClose]);

  const switchTo = (next: InboxState) => {
    setState(next);
    setSize(PAGE_SIZE);
    setExpanded(null);
  };

  const pending = inbox.data?.pending;
  const subtitle = pending === undefined ? undefined
    : pending === 0 ? 'Nothing needs your action' : `${pending} need${pending === 1 ? 's' : ''} your action`;

  let body;
  if (inbox.isPending) {
    body = <StatePanel kind="loading" title="Loading notifications" />;
  } else if (inbox.isError) {
    body = isForbidden(inbox.error)
      ? <StatePanel kind="forbidden" title="Notifications are off for you" hint="Your roles don’t include notification.view." />
      : <StatePanel kind="error" title="Couldn’t load notifications" hint={toProblem(inbox.error).message} onRetry={() => void inbox.refetch()} />;
  } else if (inbox.data.items.length === 0) {
    body = (
      <StatePanel
        kind="empty"
        title={state === 'pending' ? 'You’re all caught up' : 'Nothing snoozed'}
        hint={state === 'pending'
          ? 'Requests waiting for your decision and other notices appear here.'
          : 'Snooze a notification to park it here until later.'}
      />
    );
  } else {
    body = (
      <>
        <ul className="grid gap-2.5">
          {inbox.data.items.map((n) => (
            <NotificationRow
              key={n.id}
              item={n}
              orgId={orgId}
              expanded={expanded === n.id}
              onToggle={() => setExpanded((e) => (e === n.id ? null : n.id))}
              onNavigate={onClose}
              onDecided={(approved) => {
                setExpanded(null);
                setNotice({ id: Date.now(), kind: 'ok', text: approved ? 'Approved — the change is applied.' : 'Rejected — nothing was changed.' });
              }}
            />
          ))}
        </ul>
        {inbox.data.hasNext && (
          <div className="mt-3 flex justify-center">
            <Button variant="ghost" disabled={inbox.isFetching} onClick={() => setSize((s) => Math.min(s + PAGE_SIZE, 100))}>
              {inbox.isFetching ? 'Loading…' : `Show more (${inbox.data.totalElements - inbox.data.items.length} more)`}
            </Button>
          </div>
        )}
      </>
    );
  }

  return (
    <Drawer title="Notifications" subtitle={subtitle} onClose={onClose}>
      <div className="flex h-full min-h-0 flex-col">
        <div role="tablist" aria-label="Notification lists" className="flex gap-1 border-b border-line px-5 py-2.5">
          {(['pending', 'snoozed'] as const).map((s) => (
            <button key={s} type="button" role="tab" aria-selected={state === s}
              className={state === s ? 'fsp-chip fsp-chip-accent' : 'fsp-chip'} onClick={() => switchTo(s)}>
              {s === 'pending' ? 'Needs action' : 'Snoozed'}
            </button>
          ))}
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto p-4">
          <NoticeBar notice={notice} onDismiss={dismissNotice} />
          {body}
        </div>
      </div>
    </Drawer>
  );
}
