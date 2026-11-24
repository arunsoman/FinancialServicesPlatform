/**
 * onboard.list component — the integrated onboarding screen.
 * Shows onboarded organizations with their KYC standing:
 *   - paginated, server-side searchable + sortable org directory
 *   - each org shows its KYC case status (case no., status, risk, submitted)
 *   - "New onboarding" opens the KYC add form (the kyc.manage CaseForm) in a
 *     drawer — URL state onew=1 (the /kyc/reviews route is org-portal only, so
 *     the platform portal must not navigate there)
 *   - clicking an org without a KYC case opens the same add form; an org with
 *     a case opens it in the review queue (/kyc/reviews?case=<caseId>)
 */
import { useCallback, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useNavigate } from 'react-router-dom';
import { clsx } from 'clsx';
import { useWidgetRuntime } from '../../core/runtime';
import type { WidgetRuntimeProps } from '../../core/manifest';
import type { OnboardListConfig } from './index';
import { Button, Chip, EmptyState, Input, PageHeader, Panel, StatusBadge } from '../../../ui/atoms';
import { StatePanel } from '../../kit/StatePanel';
import { Drawer } from '../../kit/Drawer';
import { NoticeBar } from '../../kit/Notice';
import type { Notice } from '../../kit/Notice';
import { isForbidden, toProblem } from '../../kit/problem';
import { can as canAny, useSession } from '../../../web-core/session-core';
import { readProblem } from '../../../web-core/api';
import { useWidgetLink } from '../../../../signed/signed-layout';
import {
  useOrgPage, useKycCasePage, joinOrgsToKyc, DEFAULT_QUERY, PAGE_SIZES,
  type OrgListQuery, type OrgWithKyc, type KycListQuery, KYC_DEFAULT_QUERY,
} from './model';
import { emptyForm, formFromView, isEditableStatus, useCase, useCaseCommand, useMasterValues, useNextLevel } from '../../kyc/manage/model';
import type { CaseFormValues, Decision } from '../../kyc/manage/model';
import { CaseForm } from '../../kyc/manage/CaseForm';
import { CaseDetails, TABS } from '../../kyc/manage/CaseDetails';
import type { TabId } from '../../kyc/manage/CaseDetails';

const SORT_KEYS = ['legalName', 'status', 'createdAt', 'subType'] as const;
type SortKey = (typeof SORT_KEYS)[number];

function parseSort(s: string): { key: string; dir: 'asc' | 'desc' } {
  const [k, d = 'asc'] = s.split(',') as [string, string];
  return { key: (SORT_KEYS as readonly string[]).includes(k) ? (k as SortKey) : DEFAULT_QUERY.sort, dir: d === 'desc' ? 'desc' : 'asc' };
}

interface OrgTableProps {
  rows: OrgWithKyc[];
  query: OrgListQuery;
  onSort: (key: string) => void;
  /** "Users": pick the org on this screen — the users panel below shows its people */
  onUsers: (row: OrgWithKyc) => void;
  /** "KYC": open the org's KYC case here, in a drawer (review, decide) */
  onKyc: (row: OrgWithKyc) => void;
  /** the org whose users the panel below shows */
  pickedId: string | null;
  /** set when the viewer holds tenant.act_as: "Act as" per ACTIVE row (core re-decides on start) */
  actAs?: { busyId: string | null; onActAs: (row: OrgWithKyc) => void };
}

function OrgTable({ rows, query, onSort, onUsers, onKyc, pickedId, actAs }: OrgTableProps) {
  const { key: sortKey, dir } = parseSort(query.sort);
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-separate border-spacing-0 text-sm">
        <thead>
          <tr className="text-dim text-left text-[10.5px] font-bold uppercase tracking-[.14em]">
            {[
              { label: 'Organization', key: 'legalName' },
              { label: 'Type', key: 'subType' },
              { label: 'Status', key: 'status' },
              { label: 'KYC case', key: 'kyc' },
              { label: 'KYC risk', key: 'risk' },
              { label: 'Onboarded', key: 'createdAt' },
            ].map((c) => {
              const active = sortKey === c.key;
              return (
                <th key={c.label} scope="col" aria-sort={active ? (dir === 'asc' ? 'ascending' : 'descending') : 'none'} className="whitespace-nowrap border-b border-line px-3 py-2 font-bold">
                  <button
                    type="button"
                    onClick={() => onSort(c.key)}
                    aria-sort={active ? (dir === 'asc' ? 'ascending' : 'descending') : 'none'}
                    className={clsx(
                      'inline-flex items-center gap-1 uppercase tracking-[.14em] hover:text-[var(--color-text)]',
                      active && 'text-[var(--color-text)]',
                    )}
                  >
                    {c.label}
                    <span aria-hidden="true" className={clsx('text-[10px]', !active && 'opacity-30')}>
                      {active && dir === 'asc' ? '▲' : '▼'}
                    </span>
                  </button>
                </th>
              );
            })}
            <th scope="col" className="border-b border-line px-3 py-2 text-right font-bold"><span className="sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr
              key={r.id}
              data-org={r.id}
              aria-selected={pickedId === r.id}
              className={clsx('transition-colors hover:bg-raised', pickedId === r.id && 'bg-raised')}
            >
              <td className="max-w-[320px] border-b border-line px-3 py-2.5">
                <div className="truncate text-sm font-semibold">{r.legalName ?? r.id}</div>
                <div className="text-dim block truncate text-[12px] mono">{r.id.slice(0, 8)}…</div>
              </td>
              <td className="whitespace-nowrap border-b border-line px-3 py-2.5"><Chip tone="accent">{r.subType ?? r.type ?? '—'}</Chip></td>
              <td className="border-b border-line px-3 py-2.5"><StatusBadge status={r.status} /></td>
              <td className="border-b border-line px-3 py-2.5">
                {r.hasKyc ? (
                  <>
                    <div className="text-sm font-semibold">{r.kycCaseNo}</div>
                    <div className="text-[11px] text-dim">{r.kycCaseStatus}</div>
                  </>
                ) : (
                  <span className="text-dim text-[11px]">No KYC case</span>
                )}
              </td>
              <td className="border-b border-line px-3 py-2.5">
                {r.kycRiskRating ? <Chip tone={r.kycRiskRating === 'HIGH' ? 'warn' : 'neutral'}>{r.kycRiskRating}</Chip> : '—'}
              </td>
              <td className="text-dim whitespace-nowrap border-b border-line px-3 py-2.5 text-right text-xs tabular-nums">{r.createdAt ? new Date(r.createdAt).toLocaleDateString() : '—'}</td>
              <td className="whitespace-nowrap border-b border-line px-3 py-2.5 text-right">
                <div className="inline-flex gap-1.5">
                  <Button
                    variant="ghost"
                    data-action="org.users"
                    aria-label={`Users of ${r.legalName ?? r.id}`}
                    aria-pressed={pickedId === r.id}
                    onClick={() => onUsers(r)}
                  >
                    Users
                  </Button>
                  <Button
                    variant="ghost"
                    data-action="org.kyc"
                    disabled={!r.hasKyc || !r.kycCaseId}
                    title={r.hasKyc ? undefined : 'No KYC case for this organization'}
                    aria-label={`KYC of ${r.legalName ?? r.id}`}
                    onClick={() => onKyc(r)}
                  >
                    KYC
                  </Button>
                  {actAs && r.status === 'ACTIVE' && (
                    <Button
                      variant="ghost"
                      data-action="act-as"
                      disabled={actAs.busyId !== null}
                      aria-busy={actAs.busyId === r.id}
                      aria-label={`Act as ${r.legalName ?? r.id}`}
                      title="Work as this organization — manage its users, roles and onboarding. Audited as you."
                      onClick={() => actAs.onActAs(r)}
                    >
                      {actAs.busyId === r.id ? 'Starting…' : 'Act as'}
                    </Button>
                  )}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function OrgPager({ page, size, shown, hasNext, busy, onPage, onSize }: {
  page: number; size: number; shown: number; hasNext: boolean; busy: boolean;
  onPage: (p: number) => void; onSize: (s: number) => void;
}) {
  const from = page * size + 1;
  const to = page * size + shown;
  return (
    <nav aria-label="Pages" className="mt-3 flex flex-wrap items-center gap-2 border-t border-line pt-3 text-[13px]">
      <span className="text-dim tabular-nums" aria-live="polite">
        Page {page + 1}
        {shown > 0 && ` · rows ${from}–${to}`}
      </span>
      <span className="flex-1" />
      <label className="text-dim flex items-center gap-2">
        Rows per page
        <select className="fsp-field w-auto cursor-pointer py-1" value={size} onChange={(e) => onSize(Number(e.target.value))}>
          {PAGE_SIZES.map((s) => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
      </label>
      <Button variant="ghost" disabled={page === 0 || busy} onClick={() => onPage(page - 1)}>‹ Previous</Button>
      <Button variant="ghost" disabled={!hasNext || busy} onClick={() => onPage(page + 1)}>Next ›</Button>
    </nav>
  );
}

export function OnboardList(props: WidgetRuntimeProps<OnboardListConfig>) {
  const { port, context } = useWidgetRuntime();
  const session = useSession();
  // "own": the signed-in org (or the org acted as). "selected": the org picked on this screen;
  // onboarding under a picked org needs acting as its admin (it must be the org you work in).
  const scope = props.config?.scope ?? 'own';
  const workingOrg = context.orgId ?? '';
  const parentOrg = scope === 'selected' ? (context.selection.orgId ?? (session.actAs ? workingOrg : '')) : workingOrg;
  const readOnly = parentOrg !== '' && parentOrg !== workingOrg;
  const navigate = useNavigate();
  const linkTo = useWidgetLink();
  const [params, setParams] = useSearchParams();
  const query = useMemo((): OrgListQuery => {
    const [sk, sd] = (params.get('osort') ?? '').split(',');
    const opage = Number(params.get('opage'));
    const osize = Number(params.get('osize'));
    return {
      q: params.get('oq') ?? '',
      sort: sk ? sk : DEFAULT_QUERY.sort,
      dir: sd === 'asc' || sd === 'desc' ? sd : DEFAULT_QUERY.dir,
      page: Number.isInteger(opage) && opage >= 0 ? opage : 0,
      size: PAGE_SIZES.includes(osize) ? osize : DEFAULT_QUERY.size,
    };
  }, [params]);

  const [searchText, setSearchText] = useState(query.q);

  const kycQuery: KycListQuery = useMemo(() => ({
    ...KYC_DEFAULT_QUERY,
    q: '', // we match by tenant, not search
  }), []);

  const patch = useCallback((next: Record<string, string | null>) => {
    setParams((p) => {
      const n = new URLSearchParams(p);
      for (const [k, v] of Object.entries(next)) {
        if (v === null || v === '') n.delete(k);
        else n.set(k, v);
      }
      return n;
    });
  }, [setParams]);

  const setQuery = useCallback((next: Partial<OrgListQuery>) => {
    const m = { ...query, ...next };
    patch({
      oq: m.q.trim().length >= 2 ? m.q : null,
      osort: m.sort === DEFAULT_QUERY.sort && m.dir === DEFAULT_QUERY.dir ? null : `${m.sort},${m.dir}`,
      osize: m.size === DEFAULT_QUERY.size ? null : String(m.size),
      opage: m.page ? String(m.page) : null,
    });
  }, [patch, query]);

  const orgPage = useOrgPage(port, query, parentOrg);
  // KYC cases are the WORKING org's (the plugin's owner tenant); a picked org's are not readable here
  const kycPage = useKycCasePage(port, kycQuery, readOnly ? '' : parentOrg);
  // the parent's next rung (core's TenantLadder) fixes what "New onboarding" may create
  const next = useNextLevel(port, parentOrg);
  const { childSubType, orgName: parentName } = next;

  // ---- "New onboarding": the KYC add form in a drawer (URL state onew=1, ui-std §7)
  // creating a KYC application is the plugin's org.onboard_child operation —
  // core + the plugin re-decide every call; the held permission only shapes affordances.
  const canCreate = context.permissions.includes('org.onboard_child');
  const canOnboardHere = !readOnly && childSubType !== null;
  const formOpen = params.get('onew') === '1' && canOnboardHere;
  const master = useMasterValues(port, formOpen || !!params.get('case'));
  const cmd = useCaseCommand(port);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [dirty, setDirty] = useState(false);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const say = useCallback((kind: Notice['kind'], text: string) => setNotice({ id: Date.now(), kind, text }), []);

  // ---- "Act as" (plans/act-as-tenant-plan.md): step into an org below yours and work as it —
  // its users, roles, onboarding. Core decides (tenant.act_as at HOME, strictly below, ACTIVE)
  // and audits every action as the real person; the banner + Exit live in the app shell.
  // per-row "Act as" belongs to the own-org directory; on a picked-org screen the users panel offers it
  const maySudo = scope === 'own' && canAny(session.permissions, ['tenant.act_as']);
  const [actingId, setActingId] = useState<string | null>(null);
  const actAs = useCallback(async (r: OrgWithKyc) => {
    setActingId(r.id);
    setNotice(null);
    try {
      await session.startActAs(r.id);
      navigate(linkTo('users.organization') ?? '/');
    } catch (e) {
      say('bad', `Couldn’t act as ${r.legalName ?? 'this organization'} — ${readProblem(e).detail}`);
    } finally {
      setActingId(null);
    }
  }, [session, navigate, say, linkTo]);

  const closeForm = useCallback(() => {
    setDirty(false);
    setConfirmDiscard(false);
    cmd.reset();
    patch({ onew: null });
  }, [cmd, patch]);
  const requestClose = useCallback(
    () => (dirty ? setConfirmDiscard(true) : closeForm()),
    [dirty, closeForm],
  );
  const openForm = useCallback(() => {
    cmd.reset();
    setDirty(false);
    setConfirmDiscard(false);
    patch({ onew: '1' });
  }, [cmd, patch]);

  const submitForm = useCallback((values: CaseFormValues) => {
    cmd.mutate({ kind: 'create', values }, {
      onSuccess: (saved) => {
        setDirty(false);
        closeForm();
        say('ok', `${saved.legalName} was submitted for review as ${saved.caseNo}.`);
      },
    });
  }, [cmd, closeForm, say]);

  const onSort = useCallback((key: string) => {
    const cur = parseSort(query.sort);
    const nextDir = cur.key === key ? (cur.dir === 'asc' ? 'desc' : 'asc') : 'asc';
    setQuery({ sort: `${key},${nextDir}`, page: 0 });
  }, [query.sort, setQuery]);

  // ---- "Users": pick the org on this screen; the users panel (users.organization, scope "selected")
  // shows its people. The pick lives in the URL (?org=), so the view can be linked and reloaded.
  const pickedId = context.selection.orgId ?? null;
  const onUsers = useCallback((r: OrgWithKyc) => {
    context.select('orgId', r.id);
    requestAnimationFrame(() =>
      document.querySelector('[data-widget="users.organization"]')?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  }, [context]);

  // ---- "KYC": the org's KYC case in a drawer, right here (URL state case=…, ktab=…, kedit=1)
  const caseId = params.get('case');
  const kycEditing = params.get('kedit') === '1';
  const tabParam = params.get('ktab') ?? '';
  const kycTab: TabId = (TABS.map((t) => t.id) as readonly string[]).includes(tabParam) ? (tabParam as TabId) : 'company';
  const kycCase = useCase(port, caseId);
  const kycView = kycCase.data;
  const kycCmd = useCaseCommand(port);
  const onKyc = useCallback((r: OrgWithKyc) => {
    if (!r.kycCaseId) return;
    kycCmd.reset();
    patch({ case: r.kycCaseId, kedit: null, ktab: null });
  }, [kycCmd, patch]);
  const closeKyc = useCallback(() => {
    kycCmd.reset();
    patch({ case: null, kedit: null, ktab: null });
  }, [kycCmd, patch]);
  const decided: Record<Decision, string> = {
    APPROVE: 'approved — the organization is now active',
    REJECT: 'rejected',
    REQUEST_INFO: 'returned to the applicant for more information',
  };
  const decideKyc = (decision: Decision, reason: string) =>
    kycView && kycCmd.mutate({ kind: 'decide', view: kycView, decision, reason }, {
      onSuccess: (v) => say('ok', `${v.legalName} was ${decided[decision]}.`),
    });
  const submitKyc = () =>
    kycView && kycCmd.mutate({ kind: 'submit', view: kycView }, { onSuccess: (v) => say('ok', `${v.legalName} is back in the review queue.`) });
  const saveKyc = (values: CaseFormValues) =>
    kycView && kycCmd.mutate({ kind: 'update', view: kycView, values }, {
      onSuccess: (v) => {
        say('ok', `Saved changes to ${v.legalName}. Resubmit it when it’s ready for review.`);
        patch({ kedit: null });
      },
    });

  const onNewOnboarding = useCallback(() => {
    openForm();
  }, [openForm]);

  // a "selected" screen shows nothing until an organization is picked
  if (scope === 'selected' && !parentOrg) return null;
  if (orgPage.isPending || (!readOnly && kycPage.isPending)) return <StatePanel kind="loading" title="Loading organizations" />;
  if (orgPage.isError) {
    if (isForbidden(orgPage.error)) {
      return <StatePanel kind="forbidden" title="Not allowed here" hint="Onboarding needs org.create at the platform root." />;
    }
    return <StatePanel kind="error" title="Couldn’t load organizations" hint={toProblem(orgPage.error).message} onRetry={() => void orgPage.refetch()} />;
  }
  if (kycPage.isError) {
    // KYC cases may not be enabled yet — still show orgs without KYC info
    console.warn('KYC cases unavailable:', toProblem(kycPage.error).message);
  }

  const orgs = orgPage.data?.items ?? [];
  const kycCases = kycPage.data?.items ?? [];
  const rows = joinOrgsToKyc(orgs, kycCases);
  const shown = rows.length;
  const hasNext = orgPage.data?.hasNext ?? false;

  // Always-visible header + search, even when there are 0 orgs
  const level = next.label;
  const header = (
    <>
      <PageHeader
        title="Onboarding"
        subtitle={`${scope === 'selected' ? `${parentName} · ` : ''}Organizations · ${rows.length} onboarded${level ? ` · next level: ${level}` : ''}`}
        actions={
          canOnboardHere ? (
            <Button variant="primary" data-action="org.onboard" onClick={onNewOnboarding}>
              New onboarding
            </Button>
          ) : undefined
        }
      />
      {next.loaded && childSubType === null && (
        <p className="text-dim mb-3 text-[12px]">{parentName} is the last level — it doesn’t onboard organizations.</p>
      )}
      {readOnly && childSubType !== null && (
        <p className="text-dim mb-3 text-[12px]">
          To onboard a {level?.toLowerCase()} under {parentName}, act as its admin (Users panel).
        </p>
      )}
    </>
  );

  const formDrawer = formOpen && (
    <Drawer
      wide
      title="New KYC application"
      subtitle="Onboard an organization"
      onClose={requestClose}
      banner={
        confirmDiscard ? (
          <div className="flex flex-wrap items-center gap-2 border-b border-line bg-raised px-5 py-3 text-[13px]">
            <span className="flex-1">Discard your unsaved changes?</span>
            <Button variant="ghost" onClick={() => setConfirmDiscard(false)}>Keep editing</Button>
            <Button variant="danger" onClick={closeForm}>Discard</Button>
          </div>
        ) : undefined
      }
    >
      {canCreate ? (
        <CaseForm
          key={`new-${childSubType ?? ''}`}
          mode="create"
          initial={{ ...emptyForm(), subType: childSubType ?? '' }}
          lockedSubType={childSubType ?? undefined}
          lockedSubTypeHint={level ? `Below ${parentName} you can onboard a ${level.toLowerCase()} only.` : undefined}
          master={master.data}
          masterLoading={master.isPending}
          busy={cmd.isPending}
          problem={cmd.error ? toProblem(cmd.error) : null}
          onSubmit={submitForm}
          onCancel={requestClose}
          onDirtyChange={setDirty}
        />
      ) : (
        <div className="p-5">
          <EmptyState title="You can’t start applications" hint="Starting a KYC application needs the org.onboard_child permission." />
        </div>
      )}
    </Drawer>
  );

  const kycProblem = kycCmd.error ? toProblem(kycCmd.error) : null;
  const kycDrawer = caseId && (
    <Drawer
      wide
      title={kycView ? (kycEditing ? `Edit ${kycView.legalName}` : kycView.legalName) : 'KYC case'}
      subtitle={kycView ? [kycView.caseNo, kycView.registrationNumber].filter(Boolean).join(' · ') : undefined}
      onClose={closeKyc}
    >
      {kycCase.isPending ? (
        <div className="p-5"><StatePanel kind="loading" title="Loading the KYC case" /></div>
      ) : kycCase.error || !kycView ? (
        <div className="p-5">
          <EmptyState
            title={kycCase.error && toProblem(kycCase.error).status === 404 ? 'Case not found' : 'Couldn’t load this case'}
            hint={kycCase.error ? toProblem(kycCase.error).message : undefined}
          />
        </div>
      ) : kycEditing && (kycView.canEdit ?? (isEditableStatus(kycView.status) && canCreate)) ? (
        <CaseForm
          key={`${kycView.caseId}:${kycView.version}`}
          mode="edit"
          initial={formFromView(kycView)}
          master={master.data}
          masterLoading={master.isPending}
          busy={kycCmd.isPending}
          problem={kycProblem}
          onSubmit={saveKyc}
          onCancel={() => patch({ kedit: null })}
          onDirtyChange={() => undefined}
        />
      ) : (
        <CaseDetails
          view={kycView}
          canReject={context.permissions.includes('kyc.reject')}
          tab={kycTab}
          onTab={(t) => patch({ ktab: t === 'company' ? null : t })}
          master={master.data}
          port={port}
          canCreate={canCreate}
          busy={kycCmd.isPending}
          problem={kycProblem}
          onEdit={() => { kycCmd.reset(); patch({ kedit: '1' }); }}
          onSubmit={submitKyc}
          onDecide={decideKyc}
          onReload={() => { kycCmd.reset(); void kycCase.refetch(); }}
        />
      )}
    </Drawer>
  );

  const searchBox = (
    <div className="mb-4 grid gap-3">
      <label className="relative block">
        <span className="sr-only">Search organizations</span>
        <Input
          type="search"
          placeholder="Search by legal name"
          value={searchText}
          onChange={(e) => setSearchText(e.target.value)}
          onBlur={(e) => {
            const t = e.target.value.trim();
            if (t.length >= 2) setQuery({ q: t, page: 0 });
            else if (t.length === 0 && query.q.length > 0) setQuery({ q: '', page: 0 });
          }}
        />
      </label>
      <div className="flex flex-wrap items-center gap-1.5 text-[12px]">
        <span className="text-dim">Sort:</span>
        {SORT_KEYS.map((k) => {
          const cur = parseSort(query.sort);
          const active = cur.key === k;
          return (
            <button
              key={k}
              type="button"
              aria-pressed={active}
              onClick={() => onSort(k)}
              className={clsx(
                'rounded-full border px-3 py-1 text-[11px] font-semibold transition-colors',
                active ? 'border-transparent bg-accent text-invert' : 'border-line text-dim hover:border-line-strong',
              )}
            >
              {k} {active && (cur.dir === 'asc' ? '▲' : '▼')}
            </button>
          );
        })}
      </div>
    </div>
  );

  if (rows.length === 0 && query.q.trim().length >= 2) {
    return (
      <Panel className="p-4">
        {header}
        {searchBox}
        <EmptyState title="No organizations match" hint="Try a different search." />
        <NoticeBar notice={notice} onDismiss={() => setNotice(null)} />
        {formDrawer}
      </Panel>
    );
  }
  if (rows.length === 0) {
    return (
      <Panel className="p-4">
        {header}
        {searchBox}
        <EmptyState title="No organizations yet" hint="Start with “New onboarding” to create one." />
        <NoticeBar notice={notice} onDismiss={() => setNotice(null)} />
        {formDrawer}
      </Panel>
    );
  }

  return (
    <Panel className="p-4">
      {header}
      {searchBox}
      <NoticeBar notice={notice} onDismiss={() => setNotice(null)} />
      <div aria-busy={orgPage.isFetching} className={orgPage.isFetching && !orgPage.isPending ? 'opacity-70 transition-opacity' : undefined}>
        <OrgTable
          rows={rows}
          query={query}
          onSort={onSort}
          onUsers={onUsers}
          onKyc={onKyc}
          pickedId={pickedId}
          actAs={maySudo ? { busyId: actingId, onActAs: (r) => void actAs(r) } : undefined}
        />
      </div>
      <OrgPager
        page={query.page}
        size={query.size}
        shown={shown}
        hasNext={hasNext}
        busy={orgPage.isFetching}
        onPage={(p) => setQuery({ page: p })}
        onSize={(s) => setQuery({ size: s, page: 0 })}
      />
      <p className="text-dim mt-3 text-xs">
        “Users” shows an organization’s people below · “KYC” opens its KYC case · “New onboarding” creates a new case
        {maySudo && ' · “Act as” lets you work as an organization — manage its users and roles'}
      </p>
      {formDrawer}
      {kycDrawer}
    </Panel>
  );
}
