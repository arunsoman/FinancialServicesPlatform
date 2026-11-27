/**
 * approvals.queue — the checker's inbox for every four-eyes request raised in this organization
 * (users, roles, plugins, journal, KYC). Filter lives in the URL (?status=); decisions are
 * never optimistic and never auto-retried (ui-std §7); a decided row stays visible with its
 * outcome until the checker moves on.
 */
import { useCallback, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useWidgetRuntime } from '../../core/runtime';
import { PageHeader, Panel } from '../../../ui/atoms';
import { NoticeBar } from '../../kit/Notice';
import type { Notice } from '../../kit/Notice';
import { StatePanel } from '../../kit/StatePanel';
import { isForbidden, toProblem } from '../../kit/problem';
import { ApprovalCard } from './ApprovalCard';
import { useDecide, useQueue } from './model';
import type { QueueRow, StatusFilter } from './model';

export function ApprovalsQueue() {
  const { port, context } = useWidgetRuntime();
  const orgId = context.orgId ?? '';
  const [params, setParams] = useSearchParams();
  const status: StatusFilter = params.get('status') === 'ALL' ? 'ALL' : 'PENDING_CHECKER';
  const queue = useQueue(port, orgId, status);
  const decide = useDecide(port, orgId);
  const [decided, setDecided] = useState<Record<string, QueueRow>>({});
  const [activeId, setActiveId] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const dismiss = useCallback(() => setNotice(null), []);

  const rows = useMemo(() => {
    const server = queue.data ?? [];
    const ids = new Set(server.map((r) => r.id));
    const kept = Object.values(decided).filter((r) => !ids.has(r.id));
    return [...server.map((r) => decided[r.id] ?? r), ...kept];
  }, [queue.data, decided]);

  const setFilter = (next: StatusFilter) => {
    setDecided({});
    setParams((p) => {
      const n = new URLSearchParams(p);
      if (next === 'ALL') n.set('status', 'ALL');
      else n.delete('status');
      return n;
    }, { replace: true });
  };

  const onDecide = (row: QueueRow, approve: boolean, reason: string) => {
    setActiveId(row.id);
    decide.mutate({ id: row.id, approve, reason }, {
      onSuccess: () => {
        setDecided((d) => ({
          ...d,
          [row.id]: { ...row, status: approve ? 'APPROVED' : 'REJECTED', canDecide: false, blockedReason: 'DECIDED',
            reason: reason || null, decidedAt: new Date().toISOString() },
        }));
        setNotice({ id: Date.now(), kind: 'ok', text: approve ? 'Approved — the change is applied.' : 'Rejected — nothing was changed.' });
      },
    });
  };

  if (queue.isPending) return <StatePanel kind="loading" title="Loading approvals" />;
  if (queue.isError) {
    return isForbidden(queue.error)
      ? <StatePanel kind="forbidden" title="Not allowed here" hint="The approvals queue needs a checker permission in this organization." />
      : <StatePanel kind="error" title="Couldn’t load approvals" hint={toProblem(queue.error).message} onRetry={() => void queue.refetch()} />;
  }

  const pending = rows.filter((r) => r.status === 'PENDING_CHECKER').length;
  return (
    <Panel className="p-4">
      <PageHeader
        title="Approvals"
        subtitle={status === 'ALL' ? `${rows.length} requests` : `${pending} awaiting a decision`}
        actions={(
          <div role="tablist" aria-label="Filter approvals" className="flex gap-1">
            {(['PENDING_CHECKER', 'ALL'] as const).map((f) => (
              <button key={f} type="button" role="tab" aria-selected={status === f}
                className={status === f ? 'fsp-chip fsp-chip-accent' : 'fsp-chip'} onClick={() => setFilter(f)}>
                {f === 'ALL' ? 'All' : 'Pending'}
              </button>
            ))}
          </div>
        )}
      />
      <NoticeBar notice={notice} onDismiss={dismiss} />
      {rows.length === 0 ? (
        <StatePanel kind="empty" title={status === 'ALL' ? 'No requests yet' : 'Nothing awaits your decision'}
          hint="Four-eyes requests raised in this organization appear here." />
      ) : (
        <ul className="grid gap-3">
          {rows.map((r) => (
            <ApprovalCard
              key={r.id}
              row={r}
              busy={decide.isPending && activeId === r.id}
              problem={decide.isError && activeId === r.id ? toProblem(decide.error).message : null}
              onResetProblem={() => decide.reset()}
              onDecide={(approve, reason) => onDecide(r, approve, reason)}
            />
          ))}
        </ul>
      )}
    </Panel>
  );
}
