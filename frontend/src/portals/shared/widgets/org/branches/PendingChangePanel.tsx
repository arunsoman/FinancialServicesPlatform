import { useState } from 'react';
import { Alert, Button, KvPair } from '../../../ui/atoms';
import { DecisionStep } from './DecisionStep';
import { blockedText, dateTime, OPERATION_LABEL, shortRef } from './model';
import type { BranchRow, PendingChange } from './model';

export interface PendingChangePanelProps {
  branch: BranchRow;
  pending: PendingChange;
  busy: boolean;
  /** the last decision's failure, already in words (403 four-eyes texts included) */
  problem: string | null;
  onDecide: (approve: boolean, reason: string) => void;
}

/**
 * The checker surface for the one change waiting on this branch (ui-std §5.2): a read-only
 * "current → proposed" view, who asked and when, the approval reference — then Approve / Reject
 * only when core says this caller may decide. Otherwise the reason is SHOWN, never hidden.
 */
export function PendingChangePanel({ branch, pending, busy, problem, onDecide }: PendingChangePanelProps) {
  const [step, setStep] = useState<'approve' | 'reject' | null>(null);
  const blocked = blockedText(pending.blockedReason);
  return (
    <section
      aria-label="Change awaiting approval"
      className="rounded-xl border border-line-strong p-3"
      data-testid="branch-pending"
    >
      <h3 className="mb-2 text-sm font-semibold">
        Awaiting approval · {OPERATION_LABEL[pending.operation]}
      </h3>
      <div className="mb-3 text-sm" data-testid="branch-pending-diff">
        {pending.operation === 'BRANCH_CREATE' && <>Add the branch <strong>{pending.proposedName ?? branch.name}</strong>.</>}
        {pending.operation === 'BRANCH_UPDATE' && (
          <>
            Rename <span className="line-through">{branch.name}</span> → <strong>{pending.proposedName}</strong>.
          </>
        )}
        {pending.operation === 'BRANCH_DELETE' && <>Delete the branch <strong>{branch.name}</strong>. It stays active until this is approved.</>}
      </div>
      <div className="grid grid-cols-2 gap-3">
        <KvPair label="Requested by">{pending.requestedByName}</KvPair>
        <KvPair label="Requested">{dateTime(pending.requestedAt)}</KvPair>
        <KvPair label="Approval reference" mono>{shortRef(pending.approvalId)}</KvPair>
      </div>
      {problem && <div className="mt-3"><Alert kind="bad">{problem}</Alert></div>}
      {pending.canDecide ? (
        step ? (
          <DecisionStep
            approve={step === 'approve'}
            busy={busy}
            onCancel={() => setStep(null)}
            onConfirm={(reason) => onDecide(step === 'approve', reason)}
          />
        ) : (
          <div className="mt-3 flex flex-wrap justify-end gap-2">
            <Button variant="danger" data-action="branch.reject" disabled={busy} onClick={() => setStep('reject')}>Reject</Button>
            <Button variant="primary" data-action="branch.approve" disabled={busy} onClick={() => setStep('approve')}>Approve</Button>
          </div>
        )
      ) : (
        blocked && (
          <div className="mt-3" data-testid="branch-pending-blocked">
            <Alert kind="info">{blocked}</Alert>
          </div>
        )
      )}
    </section>
  );
}
