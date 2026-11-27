/**
 * kyc.manage component — list (server-side paging · search · sort · filters) · view · add ·
 * edit · submit · approve / reject / request info. All list state, the open case, the tab and
 * edit mode live in the URL (ui-std §7). Affordances follow the held permissions; the KYC
 * plugin and core re-decide every call.
 */
import { useCallback, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Alert, Button, EmptyState, PageHeader, Panel } from '../../../ui/atoms';
import { useWidgetRuntime } from '../../core/runtime';
import { Drawer } from '../../kit/Drawer';
import { NoticeBar } from '../../kit/Notice';
import type { Notice } from '../../kit/Notice';
import { CaseDetails, TABS } from './CaseDetails';
import type { TabId } from './CaseDetails';
import { CaseForm } from './CaseForm';
import { CasePager } from './CasePager';
import { CaseTable } from './CaseTable';
import { CaseTableSkeleton } from './CaseTableSkeleton';
import { CaseToolbar } from './CaseToolbar';
import {
  DEFAULT_QUERY, emptyForm, formFromView, isEditableStatus, isNotReady, parseListQuery, toProblem, useCase, useCaseCommand, useCasePage,
  useMasterValues,
  useNextLevel,
} from './model';
import type { CaseFormValues, CaseSummary, Decision, ListQuery, SortKey } from './model';

const TAB_IDS = TABS.map((t) => t.id) as readonly string[];
const DECIDED: Record<Decision, string> = {
  APPROVE: 'approved — the organization is now active',
  REJECT: 'rejected',
  REQUEST_INFO: 'returned to the applicant for more information',
};

export function KycManage() {
  const { port, context } = useWidgetRuntime();
  const held = context.permissions;
  // onboarding a child org is core's existing org.onboard_child (the plugin acts on-behalf)
  const next = useNextLevel(port, context.orgId ?? '');
  // only the CHILD level (core TenantLadder): offered once that level is known; a leaf org
  // (merchant) onboards nothing — and never a sibling, while the level is still loading either
  const canCreate = held.includes('org.onboard_child') && next.loaded && next.childSubType !== null;

  // ---- URL state
  const [params, setParams] = useSearchParams();
  const query = useMemo(() => parseListQuery(params), [params]);
  const openId = params.get('case');
  const editing = params.get('kedit') === '1';
  const tabParam = params.get('ktab') ?? '';
  const tab: TabId = TAB_IDS.includes(tabParam) ? (tabParam as TabId) : 'company';

  const patch = useCallback(
    (next: Record<string, string | null>) =>
      setParams(
        (p) => {
          const n = new URLSearchParams(p);
          for (const [k, val] of Object.entries(next)) {
            if (val === null || val === '') n.delete(k);
            else n.set(k, val);
          }
          return n;
        },
        { replace: true },
      ),
    [setParams],
  );

  const setQuery = useCallback(
    (q: Partial<ListQuery>) => {
      const m = { ...query, ...q };
      patch({
        ...(q.q !== undefined ? { kq: q.q || null } : {}),
        ...(q.status !== undefined ? { kstatus: q.status.join(',') || null } : {}),
        ...(q.risk !== undefined ? { krisk: q.risk.join(',') || null } : {}),
        ...(q.sort !== undefined || q.dir !== undefined
          ? { ksort: m.sort === DEFAULT_QUERY.sort && m.dir === DEFAULT_QUERY.dir ? null : `${m.sort},${m.dir}` }
          : {}),
        ...(q.size !== undefined ? { ksize: q.size === DEFAULT_QUERY.size ? null : String(q.size) } : {}),
        ...(q.page !== undefined ? { kpage: q.page ? String(q.page) : null } : {}),
      });
    },
    [patch, query],
  );

  // ---- server state
  const page = useCasePage(port, query);
  const formOpen = openId === 'new' || editing;
  const master = useMasterValues(port, !!openId);
  const one = useCase(port, openId && openId !== 'new' ? openId : null);
  const cmd = useCaseCommand(port);

  const [notice, setNotice] = useState<Notice | null>(null);
  const [dirty, setDirty] = useState(false);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const dismissNotice = useCallback(() => setNotice(null), []);
  const say = (kind: Notice['kind'], text: string) => setNotice({ id: Date.now(), kind, text });

  // ---- drawer navigation
  const open = (r: CaseSummary) => {
    cmd.reset();
    patch({ case: r.caseId, kedit: null, ktab: null });
  };
  const forceClose = () => {
    setDirty(false);
    setConfirmDiscard(false);
    cmd.reset();
    patch({ case: null, kedit: null, ktab: null });
  };
  const requestClose = () => (formOpen && dirty ? setConfirmDiscard(true) : forceClose());
  const backToDetails = () => {
    setDirty(false);
    setConfirmDiscard(false);
    cmd.reset();
    patch({ kedit: null });
  };

  const onSort = (key: SortKey) =>
    setQuery({ sort: key, dir: query.sort === key && query.dir === 'desc' ? 'asc' : query.sort === key ? 'desc' : key === 'legalName' ? 'asc' : 'desc', page: 0 });

  // ---- commands (never optimistic; the drawer shows its own problem)
  const view = one.data;
  const submitForm = (values: CaseFormValues) => {
    if (openId === 'new') {
      cmd.mutate({ kind: 'create', values }, {
        onSuccess: (saved) => {
          setDirty(false);
          say('ok', `${saved.legalName} was submitted for review as ${saved.caseNo}.`);
          patch({ case: saved.caseId, kedit: null, ktab: null });
        },
      });
    } else if (view) {
      cmd.mutate({ kind: 'update', view, values }, {
        onSuccess: (saved) => {
          setDirty(false);
          say('ok', `Saved changes to ${saved.legalName}. Resubmit it when it’s ready for review.`);
          patch({ kedit: null });
        },
      });
    }
  };
  const submitCase = () =>
    view && cmd.mutate({ kind: 'submit', view }, { onSuccess: (s) => say('ok', `${s.legalName} is back in the review queue.`) });
  const decide = (decision: Decision, reason: string) =>
    view && cmd.mutate({ kind: 'decide', view, decision, reason }, { onSuccess: (s) => say('ok', `${s.legalName} was ${DECIDED[decision]}.`) });
  const reload = () => {
    cmd.reset();
    void one.refetch();
  };

  // ---- list body
  const listProblem = page.error ? toProblem(page.error) : null;
  const filtersActive = query.q.trim().length >= 2 || query.status.length > 0 || query.risk.length > 0;
  const rows = page.data?.items ?? [];
  const compact = context.tier === 'xs';

  let body;
  if (page.isPending) {
    body = <CaseTableSkeleton compact={compact} />;
  } else if (isNotReady(listProblem)) {
    body = (
      <div className="grid justify-items-center gap-3">
        <EmptyState
          title="KYC isn’t enabled for this organization"
          hint="The KYC plugin is not installed, suspended, or still being set up. An administrator can enable it from Plugins."
        />
        <Button variant="ghost" onClick={() => void page.refetch()}>Check again</Button>
      </div>
    );
  } else if (listProblem?.status === 403) {
    body = <EmptyState title="You can’t review KYC cases here" hint={`${listProblem.message} Ask an administrator for kyc.review.`} />;
  } else if (listProblem) {
    body = (
      <div className="grid gap-3">
        <Alert kind="bad">Couldn’t load KYC cases — {listProblem.message}</Alert>
        <div><Button variant="ghost" onClick={() => void page.refetch()}>Try again</Button></div>
      </div>
    );
  } else if (rows.length === 0 && query.page > 0) {
    body = (
      <div className="grid justify-items-center gap-3">
        <EmptyState title="Nothing on this page" hint="The list changed since this page was opened." />
        <Button variant="ghost" onClick={() => setQuery({ page: 0 })}>Go to the first page</Button>
      </div>
    );
  } else if (rows.length === 0 && filtersActive) {
    body = (
      <div className="grid justify-items-center gap-3">
        <EmptyState title="No cases match" hint="Try a different search or filter." />
        <Button variant="ghost" onClick={() => setQuery({ q: '', status: [], risk: [], page: 0 })}>Clear filters</Button>
      </div>
    );
  } else if (rows.length === 0) {
    body = <EmptyState title="No KYC applications yet" hint={canCreate ? 'Start one with “New application”.' : undefined} />;
  } else {
    body = (
      <>
        <CaseTable rows={rows} compact={compact} query={query} selectedId={openId} onSort={onSort} onOpen={open} />
        <CasePager
          page={query.page}
          size={query.size}
          shown={rows.length}
          hasNext={page.data?.hasNext ?? false}
          busy={page.isFetching}
          onPage={(p) => setQuery({ page: p })}
          onSize={(s) => setQuery({ size: s, page: 0 })}
        />
      </>
    );
  }

  const drawerProblem = cmd.error ? toProblem(cmd.error) : null;
  const notReady = isNotReady(listProblem);

  return (
    <Panel className="min-w-0 p-4 sm:p-5">
      <PageHeader
        title="KYC"
        subtitle="Applications to verify, review and decide"
        actions={
          canCreate ? (
            <Button
              variant="primary"
              data-action="kyc.case.create"
              disabled={notReady}
              onClick={() => { cmd.reset(); patch({ case: 'new', kedit: null, ktab: null }); }}
            >
              + New application
            </Button>
          ) : undefined
        }
      />
      <NoticeBar notice={notice} onDismiss={dismissNotice} />
      {!notReady && <CaseToolbar query={query} onChange={setQuery} busy={page.isFetching} />}
      <div aria-busy={page.isFetching} className={page.isFetching && !page.isPending ? 'opacity-70 transition-opacity' : undefined}>
        {body}
      </div>

      {openId && (
        <Drawer
          wide
          title={openId === 'new' ? 'New KYC application' : editing ? `Edit ${view?.legalName ?? 'application'}` : view?.legalName ?? 'KYC case'}
          subtitle={openId === 'new' ? 'Onboard an organization' : view ? [view.caseNo, view.registrationNumber].filter(Boolean).join(' · ') : undefined}
          onClose={requestClose}
          banner={
            confirmDiscard ? (
              <div className="flex flex-wrap items-center gap-2 border-b border-line bg-raised px-5 py-3 text-[13px]">
                <span className="flex-1">Discard your unsaved changes?</span>
                <Button variant="ghost" onClick={() => setConfirmDiscard(false)}>Keep editing</Button>
                <Button variant="danger" onClick={openId === 'new' ? forceClose : backToDetails}>Discard</Button>
              </div>
            ) : undefined
          }
        >
          {openId === 'new' ? (
            canCreate ? (
              <CaseForm
                key={`new-${next.childSubType ?? ''}`}
                mode="create"
                initial={{ ...emptyForm(), subType: next.childSubType ?? '' }}
                lockedSubType={next.childSubType ?? undefined}
                lockedSubTypeHint={next.label ? `Below ${next.orgName} you can onboard a ${next.label.toLowerCase()} only.` : undefined}
                master={master.data}
                masterLoading={master.isPending}
                busy={cmd.isPending}
                problem={drawerProblem}
                onSubmit={submitForm}
                onCancel={requestClose}
                onDirtyChange={setDirty}
              />
            ) : (
              <div className="p-5">
                <EmptyState title="You can’t start applications" hint="Starting a KYC application needs the org.onboard_child permission." />
              </div>
            )
          ) : one.isPending ? (
            <div className="p-5"><CaseTableSkeleton compact rows={3} /></div>
          ) : one.error || !view ? (
            <div className="p-5">
              <EmptyState
                title={one.error && toProblem(one.error).status === 404 ? 'Case not found' : 'Couldn’t load this case'}
                hint={one.error ? toProblem(one.error).message : undefined}
              />
            </div>
          ) : editing && (view.canEdit ?? (isEditableStatus(view.status) && canCreate)) ? (
            <CaseForm
              key={`${view.caseId}:${view.version}`}
              mode="edit"
              initial={formFromView(view)}
              master={master.data}
              masterLoading={master.isPending}
              busy={cmd.isPending}
              problem={drawerProblem}
              onSubmit={submitForm}
              onCancel={() => (dirty ? setConfirmDiscard(true) : backToDetails())}
              onDirtyChange={setDirty}
            />
          ) : (
            <CaseDetails
              view={view}
              canReject={held.includes('kyc.reject')}
              tab={tab}
              onTab={(t) => patch({ ktab: t === 'company' ? null : t })}
              master={master.data}
              port={port}
              canCreate={canCreate}
              busy={cmd.isPending}
              problem={drawerProblem}
              onEdit={() => { cmd.reset(); patch({ kedit: '1' }); }}
              onSubmit={submitCase}
              onDecide={decide}
              onReload={reload}
            />
          )}
        </Drawer>
      )}
    </Panel>
  );
}
