/**
 * users.organization component — list · search/filter · view · add · modify ·
 * deactivate · activate · remove, URL-backed state per ui-std §7.
 * The list is ONE server page: search, status filter, sort and paging run in core (AGENTS §2.7).
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useWidgetRuntime } from '../../core/runtime';
import type { WidgetRuntimeProps } from '../../core/manifest';
import { can as canAny, useSession } from '../../../web-core/session-core';
import { readProblem } from '../../../web-core/api';
import type { UsersOrganizationConfig } from './index';
import { Alert, Button, EmptyState, Panel, PageHeader } from '../../../ui/atoms';
import { UserDetails } from './UserDetails';
import { Drawer } from '../../kit/Drawer';
import { UserForm } from './UserForm';
import { UserList } from './UserList';
import { UserListSkeleton } from './UserListSkeleton';
import { NoticeBar } from '../../kit/Notice';
import { Pager } from '../../kit/Pager';
import type { Notice } from '../../kit/Notice';
import { UserToolbar } from './UserToolbar';
import {
  DEFAULT_PAGE_SIZE, DEFAULT_SORT, PAGE_SIZES, SORT_OPTIONS, STATUS_FILTERS, toProblem, useRoleOptions,
  useUser, useUserCommand, useUserList,
} from './model';
import type { SortId, StatusFilter, UserCommand, UserFormValues, UserListQuery, UserRow } from './model';

const STATUS_IDS = STATUS_FILTERS.map((f) => f.id);
const SORT_IDS: readonly string[] = SORT_OPTIONS.map((o) => o.id);
const SEARCH_DEBOUNCE_MS = 300;

/** The value, once it has stopped changing for `ms` — so typing sends one search, not one per key. */
function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

export function UsersOrganization(props: WidgetRuntimeProps<UsersOrganizationConfig>) {
  const { port, context } = useWidgetRuntime();
  const session = useSession();
  const meId = session.user?.userId ?? null;
  // "own": the signed-in org, or the org being acted as (the host swaps context.orgId).
  // "selected": the org picked on this screen (?org=); while acting, the acted-as org is the default.
  const scope = props.config?.scope ?? 'own';
  const resetMode = props.config?.mode === 'reset';
  const workingOrg = context.orgId ?? '';
  const picked = scope === 'selected' ? (context.selection.orgId ?? (session.actAs ? workingOrg : '')) : '';
  const orgId = scope === 'selected' ? picked : workingOrg;
  // Another org's users, not stepped into: read-only. Changing them means acting as that org's
  // admin — core then decides as the acting principal and audits every change as the REAL person.
  const readOnly = orgId !== '' && orgId !== workingOrg;
  const maySudo = readOnly && canAny(session.permissions, ['tenant.act_as']);
  const [startingActAs, setStartingActAs] = useState(false);

  // A pick on the screen "takes you to" that org's users: on narrow tiers the list sits above
  // this panel, so bring it into view (not on first render — a linked ?org= keeps its place).
  const topRef = useRef<HTMLDivElement>(null);
  const lastPicked = useRef(picked);
  useEffect(() => {
    if (scope !== 'selected' || !picked || lastPicked.current === picked) return;
    lastPicked.current = picked;
    topRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }, [scope, picked]);

  // ---- URL state: ?q= &status= &sort= &page= &size= &user=<id|new> &edit=1
  const [params, setParams] = useSearchParams();
  const query = params.get('q') ?? '';
  const statusParam = params.get('status') as StatusFilter | null;
  const status: StatusFilter = statusParam && STATUS_IDS.includes(statusParam) ? statusParam : 'current';
  const sortParam = params.get('sort');
  const sort: SortId = sortParam && SORT_IDS.includes(sortParam) ? (sortParam as SortId) : DEFAULT_SORT;
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);
  const sizeParam = Number.parseInt(params.get('size') ?? '', 10);
  const size = (PAGE_SIZES as readonly number[]).includes(sizeParam) ? sizeParam : DEFAULT_PAGE_SIZE;
  const openId = params.get('user');
  const editing = params.get('edit') === '1';

  const patch = useCallback(
    (next: Record<string, string | null>) =>
      setParams(
        (p) => {
          const n = new URLSearchParams(p);
          for (const [k, v] of Object.entries(next)) {
            if (v === null || v === '') n.delete(k);
            else n.set(k, v);
          }
          return n;
        },
        { replace: true },
      ),
    [setParams],
  );

  // ---- server state
  const debouncedQuery = useDebounced(query.trim(), SEARCH_DEBOUNCE_MS);
  const listQuery: UserListQuery = useMemo(
    () => ({ page, size, q: debouncedQuery, status, sort }),
    [page, size, debouncedQuery, status, sort],
  );
  const list = useUserList(port, orgId, listQuery);
  const formOpen = !readOnly && (openId === 'new' || editing);
  const roles = useRoleOptions(port, orgId, formOpen);
  const cmd = useUserCommand(port, orgId);

  const [notice, setNotice] = useState<Notice | null>(null);
  const [dirty, setDirty] = useState(false);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const dismissNotice = useCallback(() => setNotice(null), []);
  const say = (kind: Notice['kind'], text: string, undo?: Notice['undo']) =>
    setNotice({ id: Date.now(), kind, text, undo });

  const users = useMemo(() => list.data?.items ?? [], [list.data]);
  const counts = list.data?.counts;
  const orgTotal = counts ? counts.CURRENT + counts.ARCHIVED : null;
  const pageRow = openId && openId !== 'new' ? users.find((u) => u.id === openId) : undefined;
  const one = useUser(port, orgId, openId, pageRow);
  const openUser: UserRow | undefined = openId && openId !== 'new' ? one.data : undefined;
  const orgName = list.data?.org?.legalName
    ?? (readOnly ? 'this organization' : session.actAs?.targetTenantName ?? session.user?.tenantName ?? 'this organization');

  const actAs = async () => {
    setStartingActAs(true);
    try {
      await session.startActAs(orgId);
      say('ok', `You are acting as ${orgName}'s admin — every change is recorded as you.`);
    } catch (e) {
      say('bad', `Couldn’t act as ${orgName} — ${readProblem(e).detail}`);
    } finally {
      setStartingActAs(false);
    }
  };

  // ---- drawer navigation
  const open = (u: UserRow) => {
    cmd.reset();
    patch({ user: u.id, edit: null });
  };
  const forceClose = () => {
    setDirty(false);
    setConfirmDiscard(false);
    cmd.reset();
    patch({ user: null, edit: null });
  };
  const requestClose = () => {
    if (formOpen && dirty) setConfirmDiscard(true);
    else forceClose();
  };
  const backToDetails = () => {
    setDirty(false);
    setConfirmDiscard(false);
    cmd.reset();
    patch({ edit: null });
  };

  // ---- commands
  const run = (c: UserCommand, onOk: (saved: UserRow) => void) =>
    cmd.mutate(c, {
      onSuccess: onOk,
      onError: (e) => {
        const p = toProblem(e);
        // drawer shows its own problem; also announce when the drawer is not the context
        if (!openId) say('bad', p.message);
      },
    });

  const submitForm = (values: UserFormValues) => {
    if (openId === 'new') {
      run({ kind: 'create', values }, (saved) => {
        setDirty(false);
        say('ok', `Request sent — ${saved.fullName} is added once a checker approves it.`);
        patch({ user: saved.id, edit: null, status: null, q: null, page: null });
      });
    } else if (openUser) {
      run({ kind: 'update', user: openUser, values, rolesEditable: openUser.id !== meId }, (saved) => {
        setDirty(false);
        say('ok', `Change requested for ${saved.fullName} — it applies once a checker approves it.`);
        patch({ edit: null });
      });
    }
  };

  const activate = (u: UserRow) =>
    run({ kind: 'activate', user: u }, (saved) => say('ok', `Reactivation of ${saved.fullName} requested — awaiting a checker.`));

  const deactivate = (u: UserRow) =>
    run({ kind: 'deactivate', user: u }, (saved) =>
      say('ok', `Deactivation of ${saved.fullName} requested — awaiting a checker.`),
    );

  const passwordReset = (u: UserRow) =>
    run({ kind: 'password-reset', user: u }, (saved) =>
      say('ok', `Password reset for ${saved.fullName} requested — a checker must approve it before the reset link is sent.`),
    );

  const remove = (u: UserRow) =>
    run({ kind: 'remove', user: u }, (saved) => {
      say('ok', `Removal of ${saved.fullName} requested — awaiting a checker.`);
      forceClose();
    });

  const resend = (u: UserRow) =>
    run({ kind: 'resend', user: u }, () => say('ok', `A new activation was sent to ${u.fullName}. The earlier one no longer works.`));

  const reload = () => {
    cmd.reset();
    void list.refetch();
    if (openId && openId !== 'new') void one.refetch();
  };

  // ---- render
  const listProblem = list.error ? toProblem(list.error) : null;
  const compact = context.tier === 'xs';
  const drawerProblem = cmd.error ? toProblem(cmd.error) : null;
  const filtersActive = query.trim() !== '' || status !== 'current';
  const searching = query.trim() !== debouncedQuery;

  // a "selected" screen shows nothing until an organization is picked in the list above
  if (scope === 'selected' && !orgId) return null;

  let body;
  if (list.isPending) {
    body = <UserListSkeleton />;
  } else if (listProblem?.status === 403) {
    body = (
      <EmptyState
        title="You can’t manage users here"
        hint={`${listProblem.message}. Ask an administrator for the user.manage permission on ${orgName}.`}
      />
    );
  } else if (listProblem) {
    body = (
      <div className="grid gap-3">
        <Alert kind="bad">Couldn’t load users — {listProblem.message}</Alert>
        <div>
          <Button variant="ghost" onClick={() => void list.refetch()}>Try again</Button>
        </div>
      </div>
    );
  } else if (orgTotal === 0) {
    body = <EmptyState title="No users yet" hint={`Add the first user for ${orgName}.`} />;
  } else if (users.length === 0 && page > 0) {
    // e.g. the last user on the last page was just removed
    body = (
      <div className="grid justify-items-center gap-3">
        <EmptyState title="Nothing on this page" hint="The list got shorter since this page was opened." />
        <Button variant="ghost" onClick={() => patch({ page: null })}>Go to the first page</Button>
      </div>
    );
  } else if (users.length === 0) {
    body = (
      <div className="grid justify-items-center gap-3">
        <EmptyState title="No users match" hint="Try a different search or status filter." />
        {filtersActive && (
          <Button variant="ghost" onClick={() => patch({ q: null, status: null, page: null })}>Clear filters</Button>
        )}
      </div>
    );
  } else {
    body = (
      <>
        <UserList users={users} compact={compact} meId={meId} selectedId={openId} onOpen={open} />
        <Pager
          page={page}
          size={size}
          shown={users.length}
          totalElements={list.data?.totalElements ?? 0}
          hasNext={list.data?.hasNext ?? false}
          sizes={PAGE_SIZES}
          busy={list.isFetching}
          noun="users"
          onPage={(n) => patch({ page: n > 0 ? String(n) : null })}
          onSize={(n) => patch({ size: n === DEFAULT_PAGE_SIZE ? null : String(n), page: null })}
        />
      </>
    );
  }

  const summary = counts
    ? `${orgName} · ${counts.ACTIVE} active${counts.DISABLED ? ` · ${counts.DISABLED} deactivated` : ''}`
    : orgName;

  return (
    <div ref={topRef} className="min-w-0 scroll-mt-4">
    <Panel className="min-w-0 p-4 sm:p-5">
      <PageHeader
        title={resetMode ? 'Reset password' : 'Users'}
        subtitle={resetMode ? `${summary} · Select a user to request a reset` : summary}
        actions={
          resetMode ? undefined : readOnly ? (
            maySudo ? (
              <Button variant="primary" data-action="act-as" onClick={() => void actAs()} disabled={startingActAs} aria-busy={startingActAs}>
                {startingActAs ? 'Starting…' : `Act as ${orgName} admin`}
              </Button>
            ) : undefined
          ) : (
            <Button variant="primary" onClick={() => { cmd.reset(); patch({ user: 'new', edit: null }); }}>
              + Add user
            </Button>
          )
        }
      />
      <NoticeBar notice={notice} onDismiss={dismissNotice} />
      {readOnly && (
        <p className="text-dim mb-3 text-[12px]">
          {maySudo
            ? `Viewing ${orgName}’s users. Act as its admin to add or change them — every change is recorded as you.`
            : `Viewing ${orgName}’s users (read-only).`}
        </p>
      )}
      <UserToolbar
        query={query}
        onQuery={(q) => patch({ q, page: null })}
        status={status}
        onStatus={(s) => patch({ status: s === 'current' ? null : s, page: null })}
        counts={counts}
        sort={sort}
        onSort={(s) => patch({ sort: s === DEFAULT_SORT ? null : s, page: null })}
      />
      <div
        aria-busy={list.isFetching || searching}
        className={(list.isFetching && !list.isPending) || searching ? 'opacity-70 transition-opacity' : undefined}
      >
        {body}
      </div>

      {openId && (
        <Drawer
          title={openId === 'new' && !readOnly ? 'Add user' : editing && !readOnly ? 'Edit user' : openUser?.fullName ?? 'User'}
          subtitle={openId === 'new' ? `New member of ${orgName}` : openUser ? `@${openUser.username} · ${orgName}` : undefined}
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
          {openId === 'new' && !readOnly ? (
            <UserForm
              key="new"
              mode="create"
              roles={roles.data}
              rolesLoading={roles.isPending}
              busy={cmd.isPending}
              problem={drawerProblem}
              onSubmit={submitForm}
              onCancel={requestClose}
              onDirtyChange={setDirty}
              onReload={reload}
            />
          ) : !openUser ? (
            one.isPending || one.isFetching ? (
              <div className="p-5"><UserListSkeleton rows={1} /></div>
            ) : (
              <div className="p-5">
                <EmptyState title="User not found" hint="They may have been moved or you may have lost access." />
              </div>
            )
          ) : editing && !readOnly ? (
            <UserForm
              key={`${openUser.id}:${openUser.version}`}
              mode="edit"
              user={openUser}
              roles={roles.data}
              rolesLoading={roles.isPending}
              rolesLockedReason={openUser.id === meId ? 'You can’t change your own roles — ask another administrator.' : undefined}
              busy={cmd.isPending}
              problem={drawerProblem}
              onSubmit={submitForm}
              onCancel={() => (dirty ? setConfirmDiscard(true) : backToDetails())}
              onDirtyChange={setDirty}
              onReload={reload}
            />
          ) : (
            <UserDetails
              user={openUser}
              isMe={openUser.id === meId}
              busy={cmd.isPending}
              problem={drawerProblem}
              onEdit={() => { cmd.reset(); patch({ edit: '1' }); }}
              onDeactivate={() => deactivate(openUser)}
              onActivate={() => activate(openUser)}
              onPasswordReset={() => passwordReset(openUser)}
              onRemove={() => remove(openUser)}
              onResend={() => resend(openUser)}
              onReload={reload}
              resetOnly={resetMode}
              readOnly={readOnly}
            />
          )}
        </Drawer>
      )}
    </Panel>
    </div>
  );
}
