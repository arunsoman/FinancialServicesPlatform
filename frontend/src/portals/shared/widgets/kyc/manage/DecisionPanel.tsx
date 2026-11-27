import { useEffect, useRef, useState } from 'react';
import { Alert, Button, KvPair } from '../../../ui/atoms';
import { TextAreaField } from '../../kit/TextAreaField';
import { absoluteTime, isMakerBlock, relativeTime } from './model';
import type { CaseView, Decision, Problem } from './model';

export interface DecisionPanelProps {
  view: CaseView;
  canReject: boolean;
  busy: boolean;
  /** last decision problem (403 MAKER_CANNOT_APPROVE renders the blocked state) */
  problem: Problem | null;
  onDecide: (decision: Decision, reason: string) => void;
}

const COPY: Record<Decision, { title: string; confirm: string; tone: 'primary' | 'danger'; body: string; reasonLabel?: string }> = {
  APPROVE: {
    title: 'Approve this application?',
    confirm: 'Approve',
    tone: 'primary',
    body: 'The organization is verified and activated, its books open, and the next periodic review is scheduled from its risk rating.',
  },
  REQUEST_INFO: {
    title: 'Ask the applicant for more information?',
    confirm: 'Send request',
    tone: 'primary',
    body: 'The case goes back to the applicant to edit and resubmit; a new review opens when they do.',
    reasonLabel: 'What do they need to provide?',
  },
  REJECT: {
    title: 'Reject this application?',
    confirm: 'Reject application',
    tone: 'danger',
    body: 'This is final. The organization stays inactive and can’t trade; the applicant sees your reason.',
    reasonLabel: 'Reason for rejecting',
  },
};

/** Who may decide is the SERVER's answer (canDecide); the panel only renders it explicitly. */
function Blocked({ view, problem }: { view: CaseView; problem: Problem | null }) {
  if (isMakerBlock(view.blockedReason) || problem?.code === 'MAKER_CANNOT_APPROVE') {
    return (
      <Alert kind="warn">
        <strong className="block">You submitted this application — another reviewer must decide.</strong>
        Four-eyes: the person who submits a KYC case can never approve, reject or return it.
      </Alert>
    );
  }
  if (problem?.code === 'CHECKER_NOT_ENABLED') {
    return (
      <Alert kind="warn">
        <strong className="block">You aren’t enabled as a checker.</strong>
        Only users with the checker flag can approve, reject or return an application — an administrator
        can set it on your user (Users → Edit → Checker).
      </Alert>
    );
  }
  if (view.blockedReason === 'PERMISSION') {
    return (
      <Alert kind="info">
        You can review this case but not decide it — deciding needs the <span className="mono">kyc.approve</span> permission.
      </Alert>
    );
  }
  if (view.blockedReason === 'CHECKER') {
    return (
      <Alert kind="warn">
        <strong className="block">You aren't enabled as a checker.</strong>
        Only users with the checker flag can approve, reject or return an application — an administrator
        can set it on your user (Users → Edit → Checker).
      </Alert>
    );
  }
  // the plugin's own sentence (e.g. "No checker assigned") — shown verbatim, never hidden
  return view.blockedReason && view.blockedReason !== 'STATE' ? <Alert kind="info">{view.blockedReason}</Alert> : null;
}

/**
 * The checker surface (ui-std §5.2): what is being decided (maker, approval revision, payload
 * hash), then Approve / Request info / Reject with an in-place confirmation. A maker never sees
 * the decision controls — they see why instead.
 */
export function DecisionPanel({ view, canReject, busy, problem, onDecide }: DecisionPanelProps) {
  const [pending, setPending] = useState<Decision | null>(null);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | undefined>();
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!pending) return;
    if (COPY[pending].reasonLabel) reasonRef.current?.focus();
    else cancelRef.current?.focus();
  }, [pending]);

  const blocked = !view.canDecide || problem?.code === 'MAKER_CANNOT_APPROVE' || problem?.code === 'CHECKER_NOT_ENABLED';

  const confirm = () => {
    if (!pending) return;
    if (pending !== 'APPROVE' && reason.trim().length < 10) {
      setError('Write at least a sentence (10+ characters) — the applicant will read it.');
      reasonRef.current?.focus();
      return;
    }
    onDecide(pending, reason);
  };

  return (
    <section aria-label="Decision" className="grid gap-3 rounded-xl border border-line-strong bg-raised p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div className="text-sm font-extrabold">Awaiting a second reviewer</div>
        {view.approval && <span className="text-dim text-[12px]">Approval revision {view.approval.rev}</span>}
      </div>
      {view.approval && (
        <div className="grid gap-3 sm:grid-cols-3">
          <KvPair label="Submitted by">{view.approval.makerName}</KvPair>
          <KvPair label="Submitted">
            <span title={absoluteTime(view.approval.submittedAt)}>{relativeTime(view.approval.submittedAt)}</span>
          </KvPair>
          <KvPair label="Payload hash" mono>
            <span title={view.approval.payloadHash}>{view.approval.payloadHash.slice(0, 19)}…</span>
          </KvPair>
        </div>
      )}

      {blocked ? (
        <Blocked view={view} problem={problem} />
      ) : pending ? (
        <div role="alertdialog" aria-label={COPY[pending].title} className="grid gap-3 rounded-xl border border-line bg-panel p-4">
          <div className="text-sm font-extrabold">{COPY[pending].title}</div>
          <p className="text-dim text-[13px] leading-relaxed">{COPY[pending].body}</p>
          {COPY[pending].reasonLabel && (
            <TextAreaField
              ref={reasonRef}
              label={COPY[pending].reasonLabel ?? ''}
              value={reason}
              maxLength={1000}
              error={error}
              hint="The applicant and auditors see this."
              onChange={(e) => {
                setReason(e.target.value);
                if (error) setError(undefined);
              }}
            />
          )}
          <div className="flex flex-wrap justify-end gap-2">
            <Button ref={cancelRef} variant="ghost" disabled={busy} onClick={() => { setPending(null); setError(undefined); }}>
              Cancel
            </Button>
            <Button variant={COPY[pending].tone} disabled={busy} aria-busy={busy} onClick={confirm}>
              {busy ? 'Working…' : COPY[pending].confirm}
            </Button>
          </div>
        </div>
      ) : (
        <div className="flex flex-wrap gap-2">
          <Button variant="primary" data-action="kyc.approve" onClick={() => setPending('APPROVE')}>Approve</Button>
          <Button variant="ghost" data-action="kyc.request-info" onClick={() => setPending('REQUEST_INFO')}>Request info</Button>
          <span className="flex-1" />
          {canReject ? (
            <Button variant="danger" data-action="kyc.reject" onClick={() => setPending('REJECT')}>Reject</Button>
          ) : (
            <span className="text-dim self-center text-[12px]">Rejecting needs <span className="mono">kyc.reject</span></span>
          )}
        </div>
      )}
    </section>
  );
}
