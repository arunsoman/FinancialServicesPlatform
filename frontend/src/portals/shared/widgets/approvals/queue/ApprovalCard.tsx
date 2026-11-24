import { useState } from 'react';
import { Link } from 'react-router-dom';
import { format, parseISO } from 'date-fns';
import { Alert, Button, Chip, KvPair } from '../../../ui/atoms';
import { useWidgetLink } from '../../../../signed/signed-layout';
import { ApprovalDecision } from './ApprovalDecision';
import { blockedText, opLabel, proposalLines, subjectLink } from './model';
import type { QueueRow } from './model';

export interface ApprovalCardProps {
  row: QueueRow;
  busy: boolean;
  problem: string | null;
  onDecide: (approve: boolean, reason: string) => void;
  onResetProblem: () => void;
}

function when(iso: string | null): string {
  if (!iso) return '—';
  try {
    return format(parseISO(iso), 'd MMM yyyy, HH:mm');
  } catch {
    return iso;
  }
}

const STATUS: Record<QueueRow['status'], { label: string; tone: 'warn' | 'ok' | 'bad' }> = {
  PENDING_CHECKER: { label: 'Awaiting approval', tone: 'warn' },
  APPROVED: { label: 'Approved', tone: 'ok' },
  REJECTED: { label: 'Rejected', tone: 'bad' },
};

/**
 * One request on the checker surface (ui-std §5.2): read-only proposal, maker identity, and the
 * decision controls only when the SERVER says this caller may decide; otherwise an explicit
 * blocked state naming why.
 */
export function ApprovalCard({ row, busy, problem, onDecide, onResetProblem }: ApprovalCardProps) {
  const [mode, setMode] = useState<'idle' | 'approve' | 'reject'>('idle');
  const status = STATUS[row.status];
  const lines = proposalLines(row.payload);
  const blocked = blockedText(row);
  const target = subjectLink(row);
  const linkTo = useWidgetLink();
  // resolved in THIS portal's published menus; no screen for it here → no link (never a wrong page)
  const to = target ? target.widgetIds.map((w) => linkTo(w, target.search)).find((t) => t !== null) ?? null : null;
  const link = target && to ? { to, label: target.label } : null;

  return (
    <li data-approval-id={row.id} className="rounded-2xl border border-line bg-panel p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="text-sm font-extrabold">{opLabel(row.operationType)}</div>
          <div className="text-dim mt-0.5 text-[12px]">
            Requested by <span className="mono">{row.makerName ?? row.makerId.slice(0, 8)}</span> · {when(row.requestedAt)}
          </div>
        </div>
        <Chip tone={status.tone}>{status.label}</Chip>
      </div>

      {lines.length > 0 && (
        <div className="mt-3 grid gap-2 rounded-xl bg-raised p-3 sm:grid-cols-2">
          {lines.map(([k, v]) => <KvPair key={k} label={k}>{v}</KvPair>)}
        </div>
      )}

      {row.status !== 'PENDING_CHECKER' && (
        <div className="text-dim mt-3 text-[12px]">
          {row.status === 'APPROVED' ? 'Approved' : 'Rejected'} by <span className="mono">{row.checkerName ?? '—'}</span> · {when(row.decidedAt)}
          {row.reason ? <> · “{row.reason}”</> : null}
        </div>
      )}

      {row.status === 'PENDING_CHECKER' && blocked && (
        <div className="mt-3"><Alert kind={row.blockedReason === 'OWN_REQUEST' ? 'info' : 'warn'}>{blocked}</Alert></div>
      )}

      <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
        {link ? <Link className="text-[12px] font-semibold underline underline-offset-2" to={link.to}>{link.label}</Link> : <span />}
        {row.canDecide && mode === 'idle' && (
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => { onResetProblem(); setMode('reject'); }}>Reject</Button>
            <Button variant="primary" onClick={() => { onResetProblem(); setMode('approve'); }}>Approve</Button>
          </div>
        )}
      </div>

      {row.canDecide && mode !== 'idle' && (
        <ApprovalDecision
          approve={mode === 'approve'}
          busy={busy}
          problem={problem}
          onCancel={() => setMode('idle')}
          onConfirm={(reason) => onDecide(mode === 'approve', reason)}
        />
      )}
    </li>
  );
}
