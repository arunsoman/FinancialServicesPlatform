import { useState } from 'react';
import { Button } from '../../../ui/atoms';
import { TextAreaField } from '../../kit/TextAreaField';

export interface DecisionStepProps {
  approve: boolean;
  busy: boolean;
  onConfirm: (reason: string) => void;
  onCancel: () => void;
}

/** The checker's confirm step: states the consequence; a rejection needs a reason the maker will read. */
export function DecisionStep({ approve, busy, onConfirm, onCancel }: DecisionStepProps) {
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const missing = !approve && reason.trim() === '';
  return (
    <div
      role="group"
      aria-label={approve ? 'Approve change' : 'Reject change'}
      className="mt-3 rounded-xl border border-line-strong bg-raised p-3"
      data-testid="branch-decision-step"
    >
      <TextAreaField
        label="Reason"
        rows={2}
        value={reason}
        data-testid="branch-decision-reason"
        onChange={(e) => setReason(e.target.value)}
        onBlur={() => setTouched(true)}
        error={touched && missing ? 'A rejection needs a reason' : undefined}
        hint={approve ? 'Optional — kept with your decision in the audit trail.' : 'Required — the maker sees this reason.'}
      />
      <div className="mt-2 flex flex-wrap justify-end gap-2">
        <Button variant="ghost" onClick={onCancel} disabled={busy}>Cancel</Button>
        <Button
          variant={approve ? 'primary' : 'danger'}
          disabled={busy || missing}
          aria-busy={busy}
          data-testid="branch-decision-confirm"
          onClick={() => onConfirm(reason)}
        >
          {busy ? 'Working…' : approve ? 'Confirm approval' : 'Confirm rejection'}
        </Button>
      </div>
    </div>
  );
}
