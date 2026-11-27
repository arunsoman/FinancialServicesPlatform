/**
 * Reset Password — the organization's users, one server page at a time (search, sort, paging
 * run in core, AGENTS §2.7), each with the ONE credential action core allows them:
 *   · Request reset    → four-eyes USER_PASSWORD_RESET; after a checker approves, the IdP
 *                        emails a one-time reset link (never by SMS, never seen by staff)
 *   · Re-send activation → an approved user who never set a password gets a new link/code
 *   · nothing          → with the reason (pending, deactivated, no email, yourself)
 * State lives in the URL (?rq= &rsort= &rpage= &rsize=) so a view can be linked.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useWidgetRuntime } from '../../core/runtime';
import { useSession } from '../../../web-core/session-core';
import { Alert, Button, EmptyState, PageHeader, Panel } from '../../../ui/atoms';
import { InlineConfirm } from '../../kit/InlineConfirm';
import { NoticeBar } from '../../kit/Notice';
import type { Notice } from '../../kit/Notice';
import { Pager } from '../../kit/Pager';
import { UserIdentity } from '../organization/UserIdentity';
import { UserStatusBadge } from '../organization/UserStatusBadge';
import { DEFAULT_PAGE_SIZE, PAGE_SIZES, SORT_OPTIONS, toProblem, userKeys, useUserList } from '../organization/model';
import type { SortId, UserRow } from '../organization/model';
import { credentialAction, maskEmail } from './model';

const SORT_IDS: readonly string[] = SORT_OPTIONS.map((o) => o.id);

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

type Pending = { user: UserRow; kind: 'reset' | 'resend' } | null;

export function PasswordReset() {
  const { port, context } = useWidgetRuntime();
  const session = useSession();
  const meId = session.user?.userId ?? null;
  const orgId = context.orgId ?? '';
  const qc = useQueryClient();

  const [params, setParams] = useSearchParams();
  const q = params.get('rq') ?? '';
  const sortParam = params.get('rsort');
  const sort: SortId = sortParam && SORT_IDS.includes(sortParam) ? (sortParam as SortId) : 'fullName,asc';
  const page = Math.max(0, Number.parseInt(params.get('rpage') ?? '0', 10) || 0);
  const sizeParam = Number.parseInt(params.get('rsize') ?? '', 10);
  const size = (PAGE_SIZES as readonly number[]).includes(sizeParam) ? sizeParam : DEFAULT_PAGE_SIZE;
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

  const debounced = useDebounced(q.trim(), 300);
  const listQuery = useMemo(() => ({ page, size, q: debounced, status: 'current' as const, sort }), [page, size, debounced, sort]);
  const list = useUserList(port, orgId, listQuery);
  const users = list.data?.items ?? [];

  const [pending, setPending] = useState<Pending>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const say = (kind: Notice['kind'], text: string) => setNotice({ id: Date.now(), kind, text });

  // never optimistic, never auto-retried (ui-std §7); the port adds the Idempotency-Key
  const act = useMutation({
    retry: 0,
    mutationFn: (p: NonNullable<Pending>) =>
      p.kind === 'reset'
        ? port.command('user.password.reset.request', { expectedVersion: p.user.version }, { tenantId: orgId, personId: p.user.id })
        : port.command('user.activation.resend', {}, { tenantId: orgId, personId: p.user.id }),
    onSuccess: (_r, p) =>
      say('ok', p.kind === 'reset'
        ? `Reset requested for ${p.user.fullName}. Once another approver accepts it in Approvals, a one-time link goes to ${maskEmail(p.user.email)}.`
        : `A new activation was sent to ${p.user.fullName}. The earlier one no longer works.`),
    onError: (e) => say('bad', toProblem(e).message),
    onSettled: () => {
      setPending(null);
      void qc.invalidateQueries({ queryKey: userKeys.org(orgId) });
    },
  });

  return (
    <Panel className="grid gap-4 p-4">
      <PageHeader title="Reset password" subtitle={list.data?.org.legalName ?? undefined} />
      <p className="text-dim text-[13px] leading-relaxed">
        Nobody here sees or sets a password. A reset needs a second approver; then the person gets a one-time link by email
        and chooses a new password themselves. People who never activated get their activation again instead.
      </p>
      <NoticeBar notice={notice} onDismiss={() => setNotice(null)} />
      {notice?.kind === 'bad' && <Alert kind="bad">{notice.text}</Alert>}

      <div className="flex flex-wrap items-end gap-3">
        <label className="grid min-w-[220px] flex-1 gap-1 text-[12px] font-semibold">
          Search
          <input
            type="search"
            className="h-9 rounded-lg border border-line bg-sunken px-3 text-sm"
            placeholder="Name, username or email"
            value={q}
            onChange={(e) => patch({ rq: e.target.value, rpage: null })}
          />
        </label>
        <label className="grid gap-1 text-[12px] font-semibold">
          Sort
          <select
            className="h-9 rounded-lg border border-line bg-sunken px-2 text-sm"
            value={sort}
            onChange={(e) => patch({ rsort: e.target.value, rpage: null })}
          >
            {SORT_OPTIONS.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
          </select>
        </label>
      </div>

      {pending && (
        <InlineConfirm
          title={pending.kind === 'reset' ? `Request a password reset for ${pending.user.fullName}?` : `Re-send ${pending.user.fullName}'s activation?`}
          confirmLabel={pending.kind === 'reset' ? 'Request reset' : 'Send activation'}
          tone="primary"
          busy={act.isPending}
          onCancel={() => setPending(null)}
          onConfirm={() => act.mutate(pending)}
        >
          {pending.kind === 'reset'
            ? <>Another approver must accept it in <Link className="underline" to="../approvals">Approvals</Link>. Then a one-time link goes to {maskEmail(pending.user.email)}; their current password keeps working until they use it.</>
            : <>A new one-time {pending.user.primaryContact === 'PHONE' ? 'SMS code' : 'email link'} goes out. Any earlier one stops working.</>}
        </InlineConfirm>
      )}

      {list.isPending ? (
        <div className="grid gap-2" aria-busy="true">
          {[0, 1, 2].map((i) => <div key={i} className="h-14 animate-pulse rounded-xl bg-sunken" />)}
        </div>
      ) : list.isError ? (
        <Alert kind="bad">
          Couldn’t load the users — {toProblem(list.error).message}{' '}
          <button type="button" className="font-extrabold underline" onClick={() => void list.refetch()}>Try again</button>
        </Alert>
      ) : users.length === 0 ? (
        <EmptyState title={debounced ? 'No user matches that search' : 'No users yet'} />
      ) : (
        <ul className="grid gap-2" data-testid="reset-list">
          {users.map((u) => {
            const a = credentialAction(u, meId);
            return (
              <li key={u.id} className="flex flex-wrap items-center gap-3 rounded-xl border border-line bg-sunken p-3">
                <div className="min-w-0 flex-1">
                  <UserIdentity user={u} me={u.id === meId} />
                  <div className="text-dim mono mt-0.5 text-[12px]">{maskEmail(u.email)}</div>
                </div>
                <UserStatusBadge status={u.status} activated={u.activated} />
                {a.kind === 'reset' && (
                  <Button variant="ghost" data-action="user.password.reset.request" disabled={act.isPending}
                    onClick={() => setPending({ user: u, kind: 'reset' })}>
                    Request reset
                  </Button>
                )}
                {a.kind === 'resend' && (
                  <Button variant="ghost" data-action="user.activation.resend" disabled={act.isPending}
                    onClick={() => setPending({ user: u, kind: 'resend' })}>
                    Re-send activation
                  </Button>
                )}
                {a.kind === 'none' && <span className="text-dim max-w-[260px] text-right text-[12px]">{a.reason}</span>}
              </li>
            );
          })}
        </ul>
      )}

      {list.data && (
        <Pager
          page={list.data.page}
          size={list.data.size}
          shown={users.length}
          totalElements={list.data.totalElements}
          hasNext={list.data.hasNext}
          sizes={PAGE_SIZES}
          busy={list.isFetching}
          noun="users"
          onPage={(p) => patch({ rpage: p ? String(p) : null })}
          onSize={(s) => patch({ rsize: String(s), rpage: null })}
        />
      )}
    </Panel>
  );
}
