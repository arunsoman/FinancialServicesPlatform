import { useState } from 'react';
import { Button } from '../../../ui/atoms';
import { TextAreaField } from '../../kit/TextAreaField';

export interface ApprovalDecisionProps {
  approve: boolean;
  busy: boolean;
  problem: string | null;
  onConfirm: (reason: string) => void;
  onCancel: () => void;
}

/** Inline decision step: states the consequence; a rejection needs a reason (server rule). */
export function ApprovalDecision({ approve, busy, problem, onConfirm, onCancel }: ApprovalDecisionProps) {
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const missing = !approve && reason.trim() === '';
  return (
    <div role="group" aria-label={approve ? 'Approve request' : 'Reject request'} className="mt-3 rounded-xl border border-line-strong bg-raised p-3">
      <TextAreaField
        label="Reason"
        rows={2}
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        onBlur={() => setTouched(true)}
        error={touched && missing ? 'A rejection needs a reason' : problem ?? undefined}
        hint={approve ? 'Optional — recorded with your decision in the audit trail.' : 'Required — the maker sees this reason.'}
      />
      <div className="mt-2 flex flex-wrap justify-end gap-2">
        <Button variant="ghost" onClick={onCancel} disabled={busy}>Cancel</Button>
        <Button
          variant={approve ? 'primary' : 'danger'}
          disabled={busy || missing}
          aria-busy={busy}
          onClick={() => onConfirm(reason.trim())}
        >
          {busy ? 'Working…' : approve ? 'Confirm approval' : 'Confirm rejection'}
        </Button>
      </div>
    </div>
  );
}
