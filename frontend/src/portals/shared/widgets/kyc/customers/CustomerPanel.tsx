/**
 * One customer case: who they are, where activation stands, and the two people who act on
 * it — the maker (re-send the activation) and the checker (activate / reject). Every
 * affordance mirrors a server rule; the server re-decides each call.
 */
import { useState } from 'react';
import { clsx } from 'clsx';
import { Alert, Button, Chip, KvPair } from '../../../ui/atoms';
import { InlineConfirm } from '../../kit/InlineConfirm';
import { TextAreaField } from '../../kit/TextAreaField';
import { absoluteTime, isMakerBlock } from '../manage/model';
import type { MasterValue, Problem } from '../manage/model';
import { STAGE_LABEL, stageOf } from './model';
import type { CustomerCaseView, Stage } from './model';

export interface CustomerPanelProps {
  view: CustomerCaseView;
  /** master values, to name the region */
  master?: MasterValue[];
  canResend: boolean;
  canApprove: boolean;
  canReject: boolean;
  busy: boolean;
  problem: Problem | null;
  onResend: () => void;
  onDecide: (decision: 'APPROVE' | 'REJECT', reason: string) => void;
}

const GENDER: Record<string, string> = { FEMALE: 'Female', MALE: 'Male', OTHER: 'Other', UNDISCLOSED: 'Prefer not to say' };
const TONE: Record<Stage, 'ok' | 'warn' | 'bad' | 'info' | 'neutral'> = {
  SETTING_UP: 'neutral', AWAITING_CUSTOMER: 'warn', AWAITING_CHECKER: 'info', ACTIVE: 'ok', REJECTED: 'bad',
};

function Step({ n, title, done, current, children }: { n: number; title: string; done: boolean; current: boolean; children: React.ReactNode }) {
  return (
    <li className="flex gap-3">
      <span
        aria-hidden="true"
        className={clsx(
          'mt-0.5 grid h-6 w-6 shrink-0 place-items-center rounded-full text-[11px] font-bold',
          done ? 'bg-status-success text-invert' : current ? 'bg-accent text-invert' : 'border border-line text-dim',
        )}
      >
        {done ? '✓' : n}
      </span>
      <div className="min-w-0">
        <div className={clsx('text-[13px] font-semibold', !done && !current && 'text-dim')}>
          {title}
          {done && <span className="sr-only"> (done)</span>}
        </div>
        <div className="text-dim text-[12px] leading-relaxed">{children}</div>
      </div>
    </li>
  );
}

function AddressCard({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-xl border border-line p-3">
      <div className="text-dim mb-1 text-[10.5px] font-bold uppercase tracking-[.14em]">{title}</div>
      {children}
    </section>
  );
}

export function CustomerPanel({ view, master, canResend, canApprove, canReject, busy, problem, onResend, onDecide }: CustomerPanelProps) {
  const c = view.customer;
  const [confirm, setConfirm] = useState<'APPROVE' | 'REJECT' | 'RESEND' | null>(null);
  const [reason, setReason] = useState('');
  if (!c) return <Alert kind="bad">This case has no customer record.</Alert>;

  const stage = stageOf({ status: view.status, contactVerified: c.contactVerified });
  const channel = c.primaryContact === 'PHONE' ? 'SMS code' : 'email link';
  const target = c.primaryContact === 'PHONE' ? c.phone : c.email;
  const maker = isMakerBlock(view.blockedReason) || problem?.code === 'MAKER_CANNOT_APPROVE';
  const pending = view.status === 'PENDING_REVIEW';
  const region = (code: string | null) =>
    master?.find((m) => m.category === 'region' && m.code === code)?.label ?? code ?? '—';

  return (
    <div className="grid gap-5 p-5">
      <div className="flex flex-wrap items-center gap-2">
        <Chip tone={TONE[stage]}>{STAGE_LABEL[stage]}</Chip>
        <span className="text-dim text-[12px] mono">{view.caseNo}</span>
      </div>

      {problem && problem.code !== 'MAKER_CANNOT_APPROVE' && <Alert kind="bad">{problem.message}</Alert>}

      <section className="grid gap-2">
        <h3 className="text-dim text-[10.5px] font-bold uppercase tracking-[.14em]">Personal details</h3>
        <div className="grid gap-x-4 gap-y-2 sm:grid-cols-2">
          <KvPair label="First name">{c.firstName}</KvPair>
          <KvPair label="Last name">{c.lastName}</KvPair>
          <KvPair label="Gender">{GENDER[c.gender] ?? c.gender}</KvPair>
          <KvPair label="Date of birth">{c.dateOfBirth}</KvPair>
          <KvPair label="Email" mono>{c.email ?? '—'}</KvPair>
          <KvPair label="Phone" mono>{c.phone ?? '—'}</KvPair>
        </div>
      </section>

      <section className="grid gap-2">
        <h3 className="text-dim text-[10.5px] font-bold uppercase tracking-[.14em]">Address</h3>
        {view.registeredAddress ? (
          <div className="grid gap-3 sm:grid-cols-2">
            <AddressCard title="Residential">
              <address className="text-sm not-italic leading-relaxed">
                {view.registeredAddress}<br />
                {view.registeredCity}{view.registeredPostal ? `, ${view.registeredPostal}` : ''}<br />
                {region(view.registeredRegion)}, Namibia
              </address>
            </AddressCard>
            <AddressCard title="Postal">
              {view.operationalAddressSame ? (
                <p className="text-dim text-sm">Same as residential</p>
              ) : (
                <address className="text-sm not-italic leading-relaxed">
                  {view.operationalAddress}<br />
                  {view.operationalCity}{view.operationalPostal ? `, ${view.operationalPostal}` : ''}<br />
                  {region(view.operationalRegion)}, Namibia
                </address>
              )}
            </AddressCard>
          </div>
        ) : (
          <p className="text-dim text-sm">No address on file.</p>
        )}
      </section>

      <section className="grid gap-3">
        <h3 className="text-dim text-[10.5px] font-bold uppercase tracking-[.14em]">Activation</h3>
        <ol className="grid gap-3">
          <Step n={1} title="Registered" done current={false}>
            By {view.makerName ?? 'a maker'}{view.submittedAt ? ` · ${absoluteTime(view.submittedAt)}` : ''}
          </Step>
          <Step n={2} title={`Customer sets their password (${channel})`} done={c.contactVerified} current={stage === 'AWAITING_CUSTOMER'}>
            {c.contactVerified
              ? `Done${c.contactVerifiedAt ? ` · ${absoluteTime(c.contactVerifiedAt)}` : ''} — they proved ${c.primaryContact === 'PHONE' ? 'the phone' : 'the email'} is theirs.`
              : `Sent to ${target ?? 'the primary contact'}${c.activationSentAt ? ` · ${absoluteTime(c.activationSentAt)}` : ''}. Waiting for the customer.`}
          </Step>
          <Step n={3} title="A reviewer activates the account" done={stage === 'ACTIVE'} current={stage === 'AWAITING_CHECKER'}>
            {stage === 'ACTIVE' ? 'Active — the customer can sign in.' : stage === 'REJECTED' ? 'Rejected — the customer can’t sign in.' : 'Someone other than the person who registered them.'}
          </Step>
        </ol>
      </section>

      {pending && !c.contactVerified && canResend && (
        confirm === 'RESEND' ? (
          <InlineConfirm
            title="Send a new activation?"
            confirmLabel={`Send ${channel}`}
            tone="primary"
            busy={busy}
            onCancel={() => setConfirm(null)}
            onConfirm={() => { onResend(); setConfirm(null); }}
          >
            A new one-time {channel} goes to {target ?? 'the primary contact'}. Any earlier one stops working.
          </InlineConfirm>
        ) : (
          <div>
            <Button variant="ghost" data-action="kyc.customers.resend" disabled={busy} onClick={() => setConfirm('RESEND')}>
              Re-send activation
            </Button>
          </div>
        )
      )}

      {pending && maker && (
        <Alert kind="warn">
          <strong className="block">You registered this customer — another reviewer must activate or reject them.</strong>
          Four-eyes: the person who registers a customer can never decide their case.
        </Alert>
      )}

      {pending && !maker && (canApprove || canReject) && (
        <section className="grid gap-3 rounded-xl border border-line p-4">
          <h3 className="text-sm font-extrabold">Decision</h3>
          {!c.contactVerified && (
            <Alert kind="info">
              Activation unlocks when the customer has set their password from the {channel}. You can reject at any time.
            </Alert>
          )}
          {confirm === 'APPROVE' || confirm === 'REJECT' ? (
            <InlineConfirm
              title={confirm === 'APPROVE' ? 'Activate this customer?' : 'Reject this customer?'}
              confirmLabel={confirm === 'APPROVE' ? 'Activate customer' : 'Reject'}
              tone={confirm === 'APPROVE' ? 'primary' : 'danger'}
              busy={busy}
              onCancel={() => setConfirm(null)}
              onConfirm={() => { onDecide(confirm, reason); setConfirm(null); }}
            >
              {confirm === 'APPROVE'
                ? 'The customer’s account becomes active and they can sign in with the password they chose.'
                : 'This is final. The account is closed and the customer can’t sign in.'}
              {confirm === 'REJECT' && (
                <div className="mt-3">
                  <TextAreaField label="Reason for rejecting" value={reason} onChange={(e) => setReason(e.target.value)} rows={2} />
                </div>
              )}
            </InlineConfirm>
          ) : (
            <div className="flex flex-wrap gap-2">
              {canApprove && (
                <Button
                  variant="primary"
                  data-action="kyc.customers.activate"
                  disabled={busy || !c.contactVerified}
                  title={c.contactVerified ? undefined : 'Waiting for the customer to activate'}
                  onClick={() => setConfirm('APPROVE')}
                >
                  Activate customer
                </Button>
              )}
              {canReject && (
                <Button variant="danger" data-action="kyc.customers.reject" disabled={busy} onClick={() => setConfirm('REJECT')}>
                  Reject
                </Button>
              )}
            </div>
          )}
        </section>
      )}

      {view.checks?.length > 0 && (
        <section className="grid gap-2">
          <h3 className="text-dim text-[10.5px] font-bold uppercase tracking-[.14em]">Screening</h3>
          {view.checks.map((ch) => (
            <div key={ch.checkId} className="flex flex-wrap items-center gap-2 text-[12.5px]">
              <Chip tone={ch.result === 'PASS' ? 'ok' : ch.result === 'FAIL' ? 'bad' : 'warn'}>{ch.result}</Chip>
              <span className="font-semibold">{ch.checkType}</span>
              <span className="text-dim">{ch.details}</span>
            </div>
          ))}
        </section>
      )}
    </div>
  );
}
