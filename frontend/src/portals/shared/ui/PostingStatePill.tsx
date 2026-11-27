/**
 * THE posting-state pill (ui-std §5.1) — every JE / PostingGroup surface renders its state
 * through this, with the canonical mapping. Nothing reads "Posted" before FINALIZED;
 * COMMIT_UNKNOWN is danger + "do not retry".
 */
import { clsx } from 'clsx';

export type PostingState =
  | 'DRAFT' | 'PENDING_CHECKER' | 'READY' | 'SUBMITTING' | 'COMMIT_UNKNOWN' | 'COMMITTED' | 'FINALIZED' | 'REJECTED';

const MAP: Record<PostingState, { label: string; hint: string; cls: string; pulse?: boolean }> = {
  DRAFT: { label: 'Draft', hint: 'Draft', cls: '' },
  PENDING_CHECKER: { label: 'Awaiting approval', hint: 'Awaiting checker', cls: 'fsp-chip-warn' },
  READY: { label: 'Ready', hint: 'Ready to submit', cls: 'fsp-chip-info' },
  SUBMITTING: { label: 'Submitting', hint: 'Submitting to ledger', cls: 'fsp-chip-info', pulse: true },
  COMMIT_UNKNOWN: { label: 'Outcome unknown', hint: 'Ledger outcome unknown — do not retry', cls: 'fsp-chip-bad' },
  COMMITTED: { label: 'Committed', hint: 'Committed — finalizing', cls: 'fsp-chip-info' },
  FINALIZED: { label: 'Posted', hint: 'Posted', cls: 'fsp-chip-ok' },
  REJECTED: { label: 'Rejected', hint: 'Rejected', cls: 'fsp-chip-bad' },
};

export interface PostingStatePillProps {
  state: string;
}

export function PostingStatePill({ state }: PostingStatePillProps) {
  const m = MAP[state as PostingState] ?? { label: state, hint: state, cls: '' };
  return (
    <span className={clsx('fsp-chip', m.cls, m.pulse && 'animate-pulse')} title={m.hint} data-posting-state={state}>
      {m.label}
    </span>
  );
}
