/**
 * org.branches — the branches of an organization. Search, filter, sort and paging run in core;
 * all of it (plus the open branch) lives in the URL. Every write is four-eyes: the maker's
 * request opens an approval, a different checker decides it, and only then does it apply.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Button, EmptyState, PageHeader, Panel } from '../../../ui/atoms';
import { useWidgetRuntime } from '../../core/runtime';
import { Drawer } from '../../kit/Drawer';
import { NoticeBar } from '../../kit/Notice';
import type { Notice } from '../../kit/Notice';
import { Pager } from '../../kit/Pager';
import { StatePanel } from '../../kit/StatePanel';
import { isForbidden, toProblem } from '../../kit/problem';
import { BranchDetails } from './BranchDetails';
import { BranchForm } from './BranchForm';
import { BranchList } from './BranchList';
import { BranchListSkeleton } from './BranchListSkeleton';
import { BranchToolbar } from './BranchToolbar';
import {
  changeBlocker, decidedNotice, DEFAULT_PAGE_SIZE, DEFAULT_SORT, DEFAULT_STATUS, filedNotice, NAME_FIELD_CODES,
  PAGE_SIZES, problemText, readListQuery, SEARCH_DEBOUNCE_MS, searchTerm,
} from './model';
import type { BranchFormValues, BranchListQuery, BranchRow } from './model';
import { useBranch, useBranchCommand, useBranchDecision, useBranchList } from './queries';

/** The value once it has stopped changing for `ms` — typing sends one search, not one per key. */
function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

export function OrgBranches() {
  const { port, context } = useWidgetRuntime();
  const orgId = context.orgId ?? '';
  const canManage = context.permissions.includes('role.manage');
  const compact = context.tier === 'xs' || context.tier === 'sm';

  // ---- URL state: ?q= &status= &sort= &page= &size= &branch=<id|new> &edit=1
  const [params, setParams] = useSearchParams();
  const urlQuery = readListQuery((k) => params.get(k));
  const openId = params.get('branch');
  const editing = params.get('edit') === '1';
  const patch = useCallback(
    (next: Record<string, string | null>) =>
      setParams((p) => {
        const n = new URLSearchParams(p);
        for (const [k, v] of Object.entries(next)) {
          if (v === null || v === '') n.delete(k);
          else n.set(k, v);
        }
        return n;
      }, { replace: true }),
    [setParams],
  );

  // ---- search: keystrokes stay local; only the debounced value reaches the URL and core
  const [text, setText] = useState(urlQuery.q);
  const debounced = useDebounced(text, SEARCH_DEBOUNCE_MS);
  const sentQ = useRef(urlQuery.q);
  useEffect(() => {
    const next = debounced.trim();
    if (next === sentQ.current) return;
    sentQ.current = next;
    patch({ q: next || null, page: null });
  }, [debounced, patch]);
  const searchNow = () => {
    const next = text.trim();
    sentQ.current = next;
    patch({ q: next || null, page: null });
  };
  const clearSearch = (alsoFilters = false) => {
    setText('');
    sentQ.current = '';
    // ONE patch: setSearchParams' updater sees the render's params, so a second call would undo the first
    patch(alsoFilters ? { q: null, status: null, page: null } : { q: null, page: null });
  };

  // ---- server state
  const { page, size, q, status, sort } = urlQuery;
  const listQuery: BranchListQuery = useMemo(
    () => ({ page, size, q: searchTerm(q), status, sort }),
    [page, size, q, status, sort],
  );
  const list = useBranchList(port, orgId, listQuery);
  const rows = useMemo(() => list.data?.items ?? [], [list.data]);
  const pageRow = openId && openId !== 'new' ? rows.find((b) => b.id === openId) : undefined;
  const one = useBranch(port, orgId, openId, pageRow);
  const open: BranchRow | undefined = openId && openId !== 'new' ? one.data : undefined;
  const cmd = useBranchCommand(port, orgId);
  const decision = useBranchDecision(port, orgId);

  const [notice, setNotice] = useState<Notice | null>(null);
  const dismissNotice = useCallback(() => setNotice(null), []);
  const say = (kind: Notice['kind'], t: string) => setNotice({ id: Date.now(), kind, text: t });

  // ---- drawer navigation
  const resetWrites = () => {
    cmd.reset();
    decision.reset();
  };
  const openBranch = (b: BranchRow) => {
    resetWrites();
    patch({ branch: b.id, edit: null });
  };
  const closeDrawer = () => {
    resetWrites();
    patch({ branch: null, edit: null });
  };

  // ---- writes (never optimistic: the reload after settle shows core's truth)
  const submitForm = (values: BranchFormValues) => {
    if (openId === 'new') {
      cmd.mutate({ kind: 'create', values }, {
        onSuccess: (c) => {
          say('ok', filedNotice('BRANCH_CREATE', values.name, c?.pendingApprovalId));
          patch({ branch: c?.branchId ?? null, edit: null });
        },
      });
    } else if (open) {
      cmd.mutate({ kind: 'update', branch: open, values }, {
        onSuccess: (c) => {
          say('ok', filedNotice('BRANCH_UPDATE', values.name, c?.pendingApprovalId));
          patch({ edit: null });
        },
      });
    }
  };
  const deleteOpen = () => {
    if (!open) return;
    const name = open.name;
    cmd.mutate({ kind: 'delete', branch: open }, {
      onSuccess: (c) => say('ok', filedNotice('BRANCH_DELETE', name, c?.pendingApprovalId)),
    });
  };
  const decideOpen = (approve: boolean, reason: string) => {
    if (!open?.pending) return;
    const subject = { name: open.name };
    const pending = open.pending;
    decision.mutate({ approvalId: pending.approvalId, approve, reason }, {
      onSuccess: () => say('ok', decidedNotice(subject, pending, approve)),
    });
  };

  const cmdProblem = cmd.error ? toProblem(cmd.error) : null;
  const cmdText = cmdProblem ? problemText(cmdProblem) : null;
  const formError = cmdProblem && cmdText ? { text: cmdText, onName: NAME_FIELD_CODES.has(cmdProblem.code) } : null;
  const decisionText = decision.error ? problemText(toProblem(decision.error)) : null;

  if (!orgId) {
    return <StatePanel kind="empty" title="No organization selected" hint="Branches belong to an organization — open this screen inside one." />;
  }
  if (list.isError && isForbidden(list.error)) {
    return <StatePanel kind="forbidden" title="Not allowed here" hint="Seeing branches needs the role.view or role.manage permission." />;
  }

  const data = list.data;
  const filtered = listQuery.q !== '' || status !== DEFAULT_STATUS;
  const subtitle = data
    ? `${data.totalElements} ${data.totalElements === 1 ? 'branch' : 'branches'}${filtered ? ' match this view' : ''}`
    : 'Loading branches';

  let body: ReactNode;
  if (list.isPending) {
    body = <BranchListSkeleton />;
  } else if (list.isError) {
    body = <StatePanel kind="error" title="Couldn’t load branches" hint={problemText(toProblem(list.error))} onRetry={() => void list.refetch()} />;
  } else if (rows.length === 0 && filtered) {
    body = (
      <div className="grid justify-items-center gap-3 py-6" data-testid="branch-no-results">
        <EmptyState title="No branches match" hint="Try another name or code, or another status." />
        <Button variant="ghost" onClick={() => clearSearch(true)}>Clear search and filters</Button>
      </div>
    );
  } else if (rows.length === 0) {
    body = (
      <div className="py-6" data-testid="branch-empty">
        <EmptyState
          title="No branches yet"
          hint={canManage ? 'Add the first branch with “New branch” — a second person approves it.' : 'Branches appear here once they are added and approved.'}
        />
      </div>
    );
  } else {
    body = <BranchList branches={rows} compact={compact} selectedId={openId} onOpen={openBranch} />;
  }

  let drawer: ReactNode = null;
  if (openId === 'new' && canManage) {
    drawer = (
      <Drawer title="New branch" subtitle="Four-eyes: a different person approves it" onClose={closeDrawer}>
        <BranchForm key="new" branch={null} busy={cmd.isPending} serverError={formError} onSubmit={submitForm} onCancel={closeDrawer} />
      </Drawer>
    );
  } else if (openId && openId !== 'new') {
    const canEdit = open !== undefined && editing && changeBlocker(open, canManage) === null;
    drawer = (
      <Drawer title={canEdit ? 'Rename branch' : (open?.name ?? 'Branch')} subtitle={open?.code} onClose={closeDrawer}>
        {open === undefined && one.isError && (
          <StatePanel kind="error" title="Couldn’t open this branch" hint={problemText(toProblem(one.error))} onRetry={() => void one.refetch()} />
        )}
        {open === undefined && !one.isError && <BranchListSkeleton rows={2} />}
        {open !== undefined && canEdit && (
          <BranchForm
            key={`edit-${open.id}`}
            branch={open}
            busy={cmd.isPending}
            serverError={formError}
            onSubmit={submitForm}
            onCancel={() => { cmd.reset(); patch({ edit: null }); }}
          />
        )}
        {open !== undefined && !canEdit && (
          <BranchDetails
            branch={open}
            canManage={canManage}
            busy={cmd.isPending}
            problem={cmdText}
            deciding={decision.isPending}
            decisionProblem={decisionText}
            onEdit={() => { cmd.reset(); patch({ edit: '1' }); }}
            onDelete={deleteOpen}
            onDecide={decideOpen}
          />
        )}
      </Drawer>
    );
  }

  return (
    // inline-size containment: the widget takes its slot's width and never widens it (a long
    // name or the table must not feed the tier ResizeObserver a wider-than-slot width)
    <Panel className="p-4 [contain:inline-size]" data-testid="org-branches">
      <PageHeader
        title="Branches"
        subtitle={subtitle}
        actions={canManage
          ? <Button variant="primary" data-action="branch.create" onClick={() => { resetWrites(); patch({ branch: 'new', edit: null }); }}>New branch</Button>
          : undefined}
      />
      {!canManage && (
        <p className="text-dim mb-3 text-[12px]">View only — adding or changing branches needs the role.manage permission.</p>
      )}
      <BranchToolbar
        text={text}
        onText={setText}
        onSearchNow={searchNow}
        onClear={() => clearSearch()}
        status={status}
        onStatus={(s) => patch({ status: s === DEFAULT_STATUS ? null : s, page: null })}
        sort={sort}
        onSort={(s) => patch({ sort: s === DEFAULT_SORT ? null : s, page: null })}
      />
      <NoticeBar notice={notice} onDismiss={dismissNotice} />
      <div aria-busy={list.isFetching} className={list.isFetching && !list.isPending ? 'opacity-70 transition-opacity' : undefined}>
        {body}
      </div>
      {data && rows.length > 0 && (
        <Pager
          page={data.page}
          size={data.size}
          shown={rows.length}
          totalElements={data.totalElements}
          hasNext={data.hasNext}
          sizes={PAGE_SIZES}
          busy={list.isFetching}
          noun="branches"
          onPage={(p) => patch({ page: p > 0 ? String(p) : null })}
          onSize={(s) => patch({ size: s === DEFAULT_PAGE_SIZE ? null : String(s), page: null })}
        />
      )}
      {drawer}
    </Panel>
  );
}
