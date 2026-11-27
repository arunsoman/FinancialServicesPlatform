import { useState } from 'react';
import { Alert, Button, KvPair } from '../../../ui/atoms';
import { Amount } from '../../../ui/Amount';
import { PostingStatePill } from '../../../ui/PostingStatePill';
import { InlineConfirm } from '../../kit/InlineConfirm';
import type { GlAccountRow } from '../../kit/books';
import type { EntryView } from './model';

export interface EntryDetailProps {
  entry: EntryView;
  accounts: GlAccountRow[];
  meId: string | null;
  canCreate: boolean;
  canApprove: boolean;
  busy: boolean;
  problem: string | null;
  onSubmit: () => void;
  onApprove: () => void;
  onCheckStatus: () => void;
}

/**
 * One entry, with the surface its viewer is entitled to (ui-std §5.2): the maker submits, a
 * DIFFERENT person with je.approve approves; COMMIT_UNKNOWN shows plan hash + original transfer
 * ids and only "Check status" — never a retry.
 */
export function EntryDetail({ entry, accounts, meId, canCreate, canApprove, busy, problem, onSubmit, onApprove, onCheckStatus }: EntryDetailProps) {
  const [confirm, setConfirm] = useState(false);
  const name = (id: string) => {
    const a = accounts.find((x) => x.id === id);
    return a ? `${a.code} · ${a.name}` : id.slice(0, 8);
  };
  const mine = meId !== null && entry.makerId === meId;

  return (
    <div className="min-w-0">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="text-base font-extrabold">{entry.description}</div>
          <div className="text-dim mono text-[12px]">{entry.entryId}</div>
        </div>
        <PostingStatePill state={entry.status} />
      </div>

      {entry.status === 'COMMIT_UNKNOWN' && (
        <div className="mt-3">
          <Alert kind="bad">
            Ledger outcome unknown — do not retry. “Check status” asks the ledger about the ORIGINAL transfers and settles the entry.
          </Alert>
        </div>
      )}
      {entry.status === 'PENDING_CHECKER' && mine && (
        <div className="mt-3"><Alert kind="info">Awaiting a checker. You made this entry, so you can’t approve it (four-eyes).</Alert></div>
      )}

      <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <KvPair label="Posting date" mono>{entry.postingDate}</KvPair>
        <KvPair label="Reference">{entry.reference ?? '—'}</KvPair>
        <KvPair label="Total debits"><Amount value={entry.totalDebits} currency={entry.currency} /></KvPair>
        <KvPair label="Total credits"><Amount value={entry.totalCredits} currency={entry.currency} /></KvPair>
      </div>

      <div className="mt-3 overflow-x-auto">
        <table className="w-full border-separate border-spacing-0 text-sm">
          <thead>
            <tr className="text-dim text-left text-[11px] uppercase tracking-[.12em]">
              <th className="border-b border-line py-2 pr-3 font-bold">#</th>
              <th className="border-b border-line py-2 pr-3 font-bold">Account</th>
              <th className="border-b border-line py-2 pr-3 text-right font-bold">Debit</th>
              <th className="border-b border-line py-2 text-right font-bold">Credit</th>
            </tr>
          </thead>
          <tbody>
            {entry.lines.map((l) => (
              <tr key={l.lineNo}>
                <td className="border-b border-line py-2 pr-3 tabular-nums">{l.lineNo}</td>
                <td className="border-b border-line py-2 pr-3">
                  <div>{name(l.glAccountId)}</div>
                  {l.memo && <div className="text-dim text-[12px]">{l.memo}</div>}
                </td>
                <td className="border-b border-line py-2 pr-3 text-right">
                  {l.direction === 'DEBIT' ? <Amount value={l.amount} currency={entry.currency} direction="debit" /> : ''}
                </td>
                <td className="border-b border-line py-2 text-right">
                  {l.direction === 'CREDIT' ? <Amount value={l.amount} currency={entry.currency} direction="credit" /> : ''}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {entry.planHash && (
        <div className="mt-3 grid gap-2 rounded-xl bg-raised p-3">
          <KvPair label="Plan hash" mono>{entry.planHash}</KvPair>
          {(entry.tbTransferIds ?? []).length > 0 && (
            <KvPair label="Ledger transfer ids" mono>{(entry.tbTransferIds ?? []).join(', ')}</KvPair>
          )}
        </div>
      )}

      {problem && <div className="mt-3"><Alert kind="bad">{problem}</Alert></div>}

      <div className="mt-4 flex flex-wrap justify-end gap-2">
        {entry.status === 'DRAFT' && mine && canCreate && (
          <Button variant="primary" disabled={busy} onClick={onSubmit} data-action="je.submit">{busy ? 'Sending…' : 'Submit for approval'}</Button>
        )}
        {entry.status === 'PENDING_CHECKER' && !mine && canApprove && !confirm && (
          <Button variant="primary" disabled={busy} onClick={() => setConfirm(true)} data-action="je.approve">Approve and post</Button>
        )}
        {entry.status === 'COMMIT_UNKNOWN' && canApprove && (
          <Button variant="primary" disabled={busy} onClick={onCheckStatus} data-action="je.check">{busy ? 'Checking…' : 'Check status'}</Button>
        )}
      </div>
      {confirm && (
        <div className="mt-3">
          <InlineConfirm title="Approve and post this entry?" confirmLabel="Approve and post" tone="primary" busy={busy}
            onCancel={() => setConfirm(false)} onConfirm={() => { setConfirm(false); onApprove(); }}>
            The entry is posted to the ledger. The state shows “Posted” only once the ledger confirms it.
          </InlineConfirm>
        </div>
      )}
    </div>
  );
}
