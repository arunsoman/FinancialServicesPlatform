import { useRef } from 'react';
import type { KeyboardEvent, ReactNode } from 'react';
import { clsx } from 'clsx';
import { Alert, Button, Chip, KvPair } from '../../../ui/atoms';
import type { WidgetDataPortLike } from '../../core/manifest';
import { CaseHistory } from './CaseHistory';
import { CaseStatusChip, CheckChip, RiskChip } from './CaseChips';
import { DecisionPanel } from './DecisionPanel';
import { absoluteTime, formatDate, formatMinor, isEditableStatus, isStalled, relativeTime, reviewDue } from './model';
import type { CaseView, Decision, MasterValue, Problem } from './model';

export const TABS = [
  { id: 'company', label: 'Company' },
  { id: 'business', label: 'Business' },
  { id: 'addresses', label: 'Addresses' },
  { id: 'owners', label: 'UBO' },
  { id: 'bank', label: 'Bank accounts' },
  { id: 'screening', label: 'Screening' },
  { id: 'history', label: 'History' },
] as const;
export type TabId = (typeof TABS)[number]['id'];

export interface CaseDetailsProps {
  view: CaseView;
  tab: TabId;
  onTab: (t: TabId) => void;
  master: MasterValue[] | undefined;
  port: WidgetDataPortLike;
  canCreate: boolean;
  /** holds kyc.reject (core's FOUR_EYES reject key, separate from kyc.approve) */
  canReject: boolean;
  busy: boolean;
  problem: Problem | null;
  onEdit: () => void;
  onSubmit: () => void;
  onDecide: (d: Decision, reason: string) => void;
  onReload: () => void;
}

function Grid({ children }: { children: ReactNode }) {
  return <div className="grid gap-x-4 gap-y-3 sm:grid-cols-2">{children}</div>;
}

function PiiNote({ masked }: { masked: boolean }) {
  if (!masked) return null;
  return (
    <p className="text-dim text-[12px]">
      ID and account numbers are masked by the KYC plugin — only the last four digits are shown.
    </p>
  );
}

/** State banner: what is happening to this case and what (if anything) the viewer can do. */
function StateBanner({ view }: { view: CaseView }) {
  switch (view.status) {
    case 'DRAFT':
      return <Alert kind="info">Draft — not submitted yet. Nobody reviews it until it’s submitted.</Alert>;
    case 'TENANT_PENDING':
    case 'SUBMITTED':
    case 'STALLED':
      return isStalled(view) ? (
        <Alert kind="bad">
          <strong className="block">Onboarding has stalled.</strong>
          The plugin keeps retrying with the same idempotency keys, so nothing is duplicated. Operators are alerted after 60 minutes.
        </Alert>
      ) : (
        <Alert kind="info">
          {view.status === 'TENANT_PENDING' ? 'Creating the organization in the platform…' : 'Running sanctions, PEP and registry screening…'} This
          usually takes under a minute.
        </Alert>
      );
    case 'REQUEST_INFO':
      return (
        <Alert kind="warn">
          <strong className="block">Returned to the applicant{view.decision ? ` by ${view.decision.checkerName}` : ''}.</strong>
          {view.decision?.reason}
        </Alert>
      );
    case 'APPROVED': {
      const due = reviewDue(view.nextReviewDate);
      return (
        <Alert kind={due === 'overdue' ? 'warn' : 'ok'}>
          Approved{view.decision ? ` by ${view.decision.checkerName} ${relativeTime(view.decision.decidedAt)}` : ''}.{' '}
          {due === 'overdue' ? 'The periodic review is overdue' : 'Next periodic review'}: {formatDate(view.nextReviewDate)}.
        </Alert>
      );
    }
    case 'REJECTED':
      return (
        <Alert kind="bad">
          <strong className="block">Rejected{view.decision ? ` by ${view.decision.checkerName}` : ''}.</strong>
          {view.decision?.reason}
        </Alert>
      );
    default:
      return null;
  }
}

/**
 * One KYC case: state banner, the checker surface when it awaits review, the applicant's data
 * in tabs (arrow keys move between tabs), and the maker's actions (edit / resubmit) when allowed.
 */
export function CaseDetails({
  view, tab, onTab, master, port, canCreate, canReject, busy, problem, onEdit, onSubmit, onDecide, onReload,
}: CaseDetailsProps) {
  const tabRefs = useRef<Record<string, HTMLButtonElement | null>>({});
  const label = (cat: MasterValue['category'], code: string | null) =>
    (code && master?.find((m) => m.category === cat && m.code === code)?.label) ?? code ?? '—';

  const onTabKey = (e: KeyboardEvent<HTMLButtonElement>) => {
    const i = TABS.findIndex((t) => t.id === tab);
    const n = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : e.key === 'Home' ? 0 : e.key === 'End' ? TABS.length - 1 : null;
    if (n === null) return;
    e.preventDefault();
    const next = TABS[(n + TABS.length) % TABS.length].id;
    onTab(next);
    tabRefs.current[next]?.focus();
  };

  const editable = isEditableStatus(view.status);
  // the plugin doesn't send canEdit yet: editable state + the onboarding permission
  const canEdit = view.canEdit ?? (editable && canCreate);
  const currency = view.currency ?? 'NAD';
  const reloadable = problem && ['STALE_WRITE', 'KYC_STATE'].includes(problem.code);

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="grid min-h-0 flex-1 content-start gap-4 overflow-y-auto p-5">
        <div className="flex flex-wrap items-center gap-1.5">
          <CaseStatusChip status={view.status} stalled={isStalled(view)} />
          <RiskChip risk={view.riskRating} />
          <Chip>{view.subType ? view.subType.charAt(0) + view.subType.slice(1).toLowerCase() : 'Customer'}</Chip>
        </div>

        {problem && problem.code !== 'MAKER_CANNOT_APPROVE' && (
          <Alert kind="bad">
            {problem.message}
            {reloadable && (
              <button type="button" className="ml-2 font-extrabold underline" onClick={onReload}>Reload case</button>
            )}
          </Alert>
        )}

        <StateBanner view={view} />
        {view.status === 'PENDING_REVIEW' && <DecisionPanel view={view} canReject={canReject} busy={busy} problem={problem} onDecide={onDecide} />}

        <div role="tablist" aria-label="Case sections" className="-mx-1 flex gap-1 overflow-x-auto border-b border-line px-1">
          {TABS.map((t) => (
            <button
              key={t.id}
              ref={(el) => { tabRefs.current[t.id] = el; }}
              id={`kyc-tab-${t.id}`}
              type="button"
              role="tab"
              aria-selected={tab === t.id}
              aria-controls="kyc-tabpanel"
              tabIndex={tab === t.id ? 0 : -1}
              onClick={() => onTab(t.id)}
              onKeyDown={onTabKey}
              className={clsx(
                '-mb-px whitespace-nowrap border-b-2 px-3 py-2 text-[13px] font-semibold transition-colors',
                tab === t.id ? 'border-accent text-[var(--color-text)]' : 'text-dim border-transparent hover:text-[var(--color-text)]',
              )}
            >
              {t.label}
              {t.id === 'owners' && <span className="ml-1 opacity-60">{view.ubos.length}</span>}
              {t.id === 'screening' && view.checks.some((c) => c.result !== 'PASS') && (
                <span className="ml-1 inline-block h-1.5 w-1.5 rounded-full bg-status-warning align-middle" aria-label="needs attention" />
              )}
            </button>
          ))}
        </div>

        <div id="kyc-tabpanel" role="tabpanel" aria-labelledby={`kyc-tab-${tab}`} className="grid gap-4">
          {tab === 'company' && (
            <Grid>
              <KvPair label="Registered name">{view.legalName}</KvPair>
              <KvPair label="Trading as">{view.dbaName ?? '—'}</KvPair>
              <KvPair label="Entity type">{label('entityType', view.entityType)}</KvPair>
              <KvPair label="Registration no." mono>{view.registrationNumber}</KvPair>
              <KvPair label="Incorporated">{formatDate(view.incorporationDate)}</KvPair>
              <KvPair label="Tax ID (TIN)" mono>{view.taxId}</KvPair>
              <KvPair label="Case no." mono>{view.caseNo}</KvPair>
              <KvPair label="Organization ID" mono>{view.subjectTenantId ?? 'Not created yet'}</KvPair>
            </Grid>
          )}
          {tab === 'business' && (
            <Grid>
              <KvPair label="Merchant category">{label('mcc', view.mccCode)}</KvPair>
              <KvPair label="Website">{view.websiteUrl ?? '—'}</KvPair>
              <KvPair label="Expected monthly volume">
                <span className="tabular-nums">{formatMinor(view.expectedMonthlyVolume, currency)}</span>
              </KvPair>
              <KvPair label="Average ticket">
                <span className="tabular-nums">{formatMinor(view.avgTicketSize, currency)}</span>
              </KvPair>
            </Grid>
          )}
          {tab === 'addresses' && (
            <div className="grid gap-4 sm:grid-cols-2">
              <section className="rounded-xl border border-line p-3">
                <div className="text-dim mb-1 text-[10.5px] font-bold uppercase tracking-[.14em]">Registered</div>
                <address className="text-sm not-italic leading-relaxed">
                  {view.registeredAddress}<br />
                  {view.registeredCity}{view.registeredPostal ? `, ${view.registeredPostal}` : ''}<br />
                  {label('region', view.registeredRegion)}, Namibia
                </address>
              </section>
              <section className="rounded-xl border border-line p-3">
                <div className="text-dim mb-1 text-[10.5px] font-bold uppercase tracking-[.14em]">Operating</div>
                {view.operationalAddressSame ? (
                  <p className="text-dim text-sm">Same as registered</p>
                ) : (
                  <address className="text-sm not-italic leading-relaxed">
                    {view.operationalAddress}<br />
                    {view.operationalCity}{view.operationalPostal ? `, ${view.operationalPostal}` : ''}<br />
                    {label('region', view.operationalRegion)}, Namibia
                  </address>
                )}
              </section>
            </div>
          )}
          {tab === 'owners' && (
            <>
              <PiiNote masked={!!view.piiMasked} />
              <ul className="grid gap-2">
                {view.ubos.map((u) => (
                  <li key={u.id} className="rounded-xl border border-line p-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <span className="font-semibold">{u.fullName}</span>
                      <span className="flex gap-1.5">
                        {u.isPep && <Chip tone="warn">PEP</Chip>}
                        <Chip tone="accent">{u.ownershipPct}%</Chip>
                      </span>
                    </div>
                    <div className="mt-2 grid gap-3 sm:grid-cols-3">
                      <KvPair label={label('idDocType', u.idDocType)} mono>{u.idNumber}</KvPair>
                      <KvPair label="Nationality">{label('nationality', u.nationality ?? null)}</KvPair>
                      <KvPair label="Born">{formatDate(u.dob)}</KvPair>
                    </div>
                  </li>
                ))}
              </ul>
            </>
          )}
          {tab === 'bank' && (
            <>
              <PiiNote masked={!!view.piiMasked} />
              <ul className="grid gap-2">
                {view.bankAccounts.map((b) => (
                  <li key={b.id} className="rounded-xl border border-line p-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <span className="font-semibold">{label('bank', b.bankName)}</span>
                      <Chip tone={b.status === 'VERIFIED' ? 'ok' : 'neutral'}>{b.status === 'VERIFIED' ? 'Verified' : 'Pending'}</Chip>
                    </div>
                    <div className="mt-2 grid gap-3 sm:grid-cols-3">
                      <KvPair label="Account" mono>{b.accountNumber}</KvPair>
                      <KvPair label="Branch code" mono>{b.branchCode}</KvPair>
                      <KvPair label="Type">{label('accountType', b.accountType)}</KvPair>
                    </div>
                    <div className="text-dim mt-2 truncate text-[12px]">Holder: {b.holderName}</div>
                  </li>
                ))}
              </ul>
            </>
          )}
          {tab === 'screening' && (
            view.checks.length === 0 ? (
              <p className="text-dim text-sm">Screening runs when the application is submitted.</p>
            ) : (
              <ul className="grid gap-2">
                {view.checks.map((c) => (
                  <li key={c.checkId} className="flex items-start gap-3 rounded-xl border border-line p-3">
                    <div className="min-w-0 flex-1">
                      <div className="text-sm font-semibold">
                        {c.checkType === 'BIPA_REGISTRY' ? 'BIPA registry' : c.checkType === 'PEP' ? 'Politically exposed persons' : 'Sanctions lists'}
                        {c.simulated && <span className="text-dim ml-2 text-[11px] font-normal">simulated</span>}
                      </div>
                      <div className="text-dim text-[12px]">{c.details}</div>
                      <div className="text-dim text-[11px]" title={absoluteTime(c.checkedAt)}>{relativeTime(c.checkedAt)}</div>
                    </div>
                    <CheckChip result={c.result} />
                  </li>
                ))}
              </ul>
            )
          )}
          {tab === 'history' && <CaseHistory port={port} caseId={view.caseId} />}
        </div>
      </div>

      {editable && (
        <div className="flex flex-wrap items-center gap-2 border-t border-line p-4">
          {canEdit ? (
            <>
              <Button variant="ghost" data-action="kyc.case.update" onClick={onEdit} disabled={busy}>Edit application</Button>
              <span className="flex-1" />
              <Button variant="primary" data-action="kyc.case.submit" onClick={onSubmit} disabled={busy} aria-busy={busy}>
                {busy ? 'Submitting…' : view.status === 'DRAFT' ? 'Submit for review' : 'Resubmit for review'}
              </Button>
            </>
          ) : (
            <p className="text-dim text-[12px]">
              {canCreate ? 'This case can’t be edited right now.' : <>Editing and submitting need the <span className="mono">org.onboard_child</span> permission.</>}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
