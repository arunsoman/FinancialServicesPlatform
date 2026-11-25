/**
 * kyc.customers component — the customer directory of the KYC plugin, built like the
 * organization screen: server-side paged / searched / sorted list, "New customer" opening the
 * registration in a drawer (URL state cnew=1), a row opening the case (URL state ccase=<id>).
 */
import { useCallback, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { clsx } from 'clsx';
import { useWidgetRuntime } from '../../core/runtime';
import { Button, Chip, EmptyState, Input, PageHeader, Panel, StatusBadge } from '../../../ui/atoms';
import { StatePanel } from '../../kit/StatePanel';
import { Drawer } from '../../kit/Drawer';
import { NoticeBar } from '../../kit/Notice';
import type { Notice } from '../../kit/Notice';
import { isForbidden } from '../../kit/problem';
import { isNotReady, toProblem, useMasterValues } from '../manage/model';
import { CustomerForm } from './CustomerForm';
import { CustomerPanel } from './CustomerPanel';
import {
  DEFAULT_QUERY, PAGE_SIZES, SORT_KEYS, STAGE_LABEL, emptyCustomer, parseQuery, stageOf,
  useCustomerCase, useCustomerCommand, useCustomerPage,
} from './model';
import type { CustomerFormValues, CustomerQuery, CustomerRow } from './model';

const COLUMNS = [
  { label: 'Customer', key: 'legalName' },
  { label: 'Activation sent to', key: null },
  { label: 'Stage', key: 'status' },
  { label: 'Registered', key: 'submittedAt' },
] as const;

const STATUS_FILTERS: { value: CustomerQuery['status']; label: string }[] = [
  { value: '', label: 'All' },
  { value: 'PENDING_REVIEW', label: 'In progress' },
  { value: 'APPROVED', label: 'Active' },
  { value: 'REJECTED', label: 'Rejected' },
];

function CustomerTable({ rows, query, onSort, onOpen }: {
  rows: CustomerRow[]; query: CustomerQuery; onSort: (k: string) => void; onOpen: (r: CustomerRow) => void;
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-separate border-spacing-0 text-sm">
        <thead>
          <tr className="text-dim text-left text-[10.5px] font-bold uppercase tracking-[.14em]">
            {COLUMNS.map((c) => {
              const active = c.key !== null && query.sort === c.key;
              const aria = active ? (query.dir === 'asc' ? 'ascending' : 'descending') : 'none';
              return (
                <th key={c.label} scope="col" aria-sort={c.key ? aria : undefined} className="whitespace-nowrap border-b border-line px-3 py-2 font-bold">
                  {c.key ? (
                    <button
                      type="button"
                      onClick={() => onSort(c.key)}
                      className={clsx('inline-flex items-center gap-1 uppercase tracking-[.14em] hover:text-[var(--color-text)]', active && 'text-[var(--color-text)]')}
                    >
                      {c.label}
                      <span aria-hidden="true" className={clsx('text-[10px]', !active && 'opacity-30')}>{active && query.dir === 'asc' ? '▲' : '▼'}</span>
                    </button>
                  ) : c.label}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const stage = stageOf(r);
            return (
              <tr
                key={r.caseId}
                tabIndex={0}
                onClick={() => onOpen(r)}
                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpen(r); } }}
                className="cursor-pointer outline-none transition-colors hover:bg-raised focus-visible:bg-raised"
              >
                <td className="max-w-[320px] border-b border-line px-3 py-2.5">
                  <div className="truncate text-sm font-semibold">{r.legalName}</div>
                  <div className="text-dim truncate text-[12px] mono">{r.caseNo}</div>
                </td>
                <td className="whitespace-nowrap border-b border-line px-3 py-2.5 text-[12.5px]">
                  {r.primaryContact === 'PHONE' ? 'Phone · SMS code' : 'Email · link'}
                </td>
                <td className="border-b border-line px-3 py-2.5">
                  {stage === 'ACTIVE' || stage === 'REJECTED'
                    ? <StatusBadge status={stage === 'ACTIVE' ? 'ACTIVE' : 'REJECTED'} />
                    : <Chip tone={stage === 'AWAITING_CHECKER' ? 'info' : 'warn'}>{STAGE_LABEL[stage]}</Chip>}
                </td>
                <td className="text-dim whitespace-nowrap border-b border-line px-3 py-2.5 text-right text-xs tabular-nums">
                  {r.submittedAt ? new Date(r.submittedAt).toLocaleDateString() : '—'}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export function KycCustomers() {
  const { port, context } = useWidgetRuntime();
  const [params, setParams] = useSearchParams();
  const query = useMemo(() => parseQuery(params), [params]);
  const [searchText, setSearchText] = useState(query.q);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [dirty, setDirty] = useState(false);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const say = useCallback((kind: Notice['kind'], text: string) => setNotice({ id: Date.now(), kind, text }), []);

  const perms = context.permissions;
  const canCreate = perms.includes('org.onboard_child');
  const canApprove = perms.includes('kyc.approve');
  const canReject = perms.includes('kyc.reject');

  const patch = useCallback((next: Record<string, string | null>) => {
    setParams((p) => {
      const n = new URLSearchParams(p);
      for (const [k, val] of Object.entries(next)) {
        if (val === null || val === '') n.delete(k);
        else n.set(k, val);
      }
      return n;
    });
  }, [setParams]);

  const setQuery = useCallback((next: Partial<CustomerQuery>) => {
    const m = { ...query, ...next };
    patch({
      cq: m.q.trim().length >= 2 ? m.q.trim() : null,
      cstatus: m.status || null,
      csort: m.sort === DEFAULT_QUERY.sort && m.dir === DEFAULT_QUERY.dir ? null : `${m.sort},${m.dir}`,
      csize: m.size === DEFAULT_QUERY.size ? null : String(m.size),
      cpage: m.page ? String(m.page) : null,
    });
  }, [patch, query]);

  const page = useCustomerPage(port, query);
  const formOpen = params.get('cnew') === '1' && canCreate;
  const caseId = params.get('ccase');
  const detail = useCustomerCase(port, caseId);
  const master = useMasterValues(port, formOpen || !!caseId);
  const cmd = useCustomerCommand(port);

  const closeForm = useCallback(() => {
    setDirty(false);
    setConfirmDiscard(false);
    cmd.reset();
    patch({ cnew: null });
  }, [cmd, patch]);
  const requestClose = useCallback(() => (dirty ? setConfirmDiscard(true) : closeForm()), [dirty, closeForm]);
  const openForm = useCallback(() => { cmd.reset(); setDirty(false); patch({ cnew: '1', ccase: null }); }, [cmd, patch]);
  const openCase = useCallback((r: CustomerRow) => { cmd.reset(); patch({ ccase: r.caseId, cnew: null }); }, [cmd, patch]);
  const closeCase = useCallback(() => { cmd.reset(); patch({ ccase: null }); }, [cmd, patch]);

  const submit = useCallback((values: CustomerFormValues) => {
    cmd.mutate({ kind: 'create', values }, {
      onSuccess: (saved) => {
        setDirty(false);
        closeForm();
        const how = values.primaryContact === 'PHONE' ? 'an SMS code to their phone' : 'a link to their email';
        say('ok', `${values.firstName} ${values.lastName} was registered${'caseNo' in saved ? ` as ${saved.caseNo}` : ''}. We sent ${how} — they set their own password, then a reviewer activates the account.`);
      },
    });
  }, [cmd, closeForm, say]);

  const onSort = useCallback((key: string) => {
    const dir = query.sort === key ? (query.dir === 'asc' ? 'desc' : 'asc') : 'asc';
    if ((SORT_KEYS as readonly string[]).includes(key)) setQuery({ sort: key as CustomerQuery['sort'], dir, page: 0 });
  }, [query.sort, query.dir, setQuery]);

  const header = (
    <PageHeader
      title="Customers"
      subtitle="Customer KYC · registered by staff, activated by the customer, approved by a second reviewer"
      actions={canCreate ? <Button variant="primary" data-action="kyc.customers.new" onClick={openForm}>New customer</Button> : undefined}
    />
  );

  const formDrawer = formOpen && (
    <Drawer
      wide
      title="New customer"
      subtitle="Customer KYC"
      onClose={requestClose}
      banner={confirmDiscard ? (
        <div className="flex flex-wrap items-center gap-2 border-b border-line bg-raised px-5 py-3 text-[13px]">
          <span className="flex-1">Discard your unsaved changes?</span>
          <Button variant="ghost" onClick={() => setConfirmDiscard(false)}>Keep editing</Button>
          <Button variant="danger" onClick={closeForm}>Discard</Button>
        </div>
      ) : undefined}
    >
      <CustomerForm
        initial={emptyCustomer()}
        master={master.data}
        masterLoading={master.isPending}
        busy={cmd.isPending}
        problem={cmd.error ? toProblem(cmd.error) : null}
        onSubmit={submit}
        onCancel={requestClose}
        onDirtyChange={setDirty}
      />
    </Drawer>
  );

  const caseDrawer = caseId && (
    <Drawer
      title={detail.data?.customer ? `${detail.data.customer.firstName} ${detail.data.customer.lastName}` : 'Customer'}
      subtitle="Customer KYC"
      onClose={closeCase}
    >
      {detail.isPending ? (
        <StatePanel kind="loading" title="Loading customer" />
      ) : detail.isError ? (
        <StatePanel kind="error" title="Couldn’t load this customer" hint={toProblem(detail.error).message} onRetry={() => void detail.refetch()} />
      ) : (
        <CustomerPanel
          view={detail.data}
          master={master.data}
          canResend={canCreate}
          canApprove={canApprove}
          canReject={canReject}
          busy={cmd.isPending}
          problem={cmd.error ? toProblem(cmd.error) : null}
          onResend={() => cmd.mutate({ kind: 'resend', view: detail.data }, {
            onSuccess: () => say('ok', 'A new activation was sent. The earlier one no longer works.'),
          })}
          onDecide={(decision, reason) => cmd.mutate({ kind: 'decide', view: detail.data, decision, reason }, {
            onSuccess: () => say('ok', decision === 'APPROVE' ? 'Customer activated — they can sign in now.' : 'Customer rejected.'),
          })}
        />
      )}
    </Drawer>
  );

  const searchBox = (
    <div className="mb-4 grid gap-3">
      <label className="relative block">
        <span className="sr-only">Search customers</span>
        <Input
          type="search"
          placeholder="Search by name, case no. or email"
          value={searchText}
          onChange={(e) => setSearchText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') setQuery({ q: searchText, page: 0 }); }}
          onBlur={(e) => {
            const t = e.target.value.trim();
            if (t.length >= 2 || (t.length === 0 && query.q)) setQuery({ q: t, page: 0 });
          }}
        />
      </label>
      <div className="flex flex-wrap items-center gap-1.5 text-[12px]" role="group" aria-label="Filter by stage">
        {STATUS_FILTERS.map((f) => (
          <button
            key={f.label}
            type="button"
            aria-pressed={query.status === f.value}
            onClick={() => setQuery({ status: f.value, page: 0 })}
            className={clsx(
              'rounded-full border px-3 py-1 text-[11px] font-semibold transition-colors',
              query.status === f.value ? 'border-transparent bg-accent text-invert' : 'border-line text-dim hover:border-line-strong',
            )}
          >
            {f.label}
          </button>
        ))}
      </div>
    </div>
  );

  if (page.isPending) return <StatePanel kind="loading" title="Loading customers" />;
  if (page.isError) {
    const p = toProblem(page.error);
    if (isForbidden(page.error)) return <StatePanel kind="forbidden" title="Not allowed here" hint="Customer KYC needs kyc.review." />;
    if (isNotReady(p)) return <StatePanel kind="error" title="KYC isn’t available for this organization" hint={p.message || 'The KYC plugin is not installed, suspended, or still being set up.'} onRetry={() => void page.refetch()} />;
    return <StatePanel kind="error" title="Couldn’t load customers" hint={p.message} onRetry={() => void page.refetch()} />;
  }

  const rows = page.data?.items ?? [];
  return (
    <Panel className="p-4">
      {header}
      {searchBox}
      <NoticeBar notice={notice} onDismiss={() => setNotice(null)} />
      {rows.length === 0 ? (
        <EmptyState
          title={query.q || query.status ? 'No customers match' : 'No customers yet'}
          hint={query.q || query.status ? 'Try a different search or filter.' : canCreate ? 'Start with “New customer”.' : undefined}
        />
      ) : (
        <div aria-busy={page.isFetching} className={page.isFetching ? 'opacity-70 transition-opacity' : undefined}>
          <CustomerTable rows={rows} query={query} onSort={onSort} onOpen={openCase} />
        </div>
      )}
      <nav aria-label="Pages" className="mt-3 flex flex-wrap items-center gap-2 border-t border-line pt-3 text-[13px]">
        <span className="text-dim tabular-nums" aria-live="polite">Page {query.page + 1}</span>
        <span className="flex-1" />
        <label className="text-dim flex items-center gap-2">
          Rows per page
          <select className="fsp-field w-auto cursor-pointer py-1" value={query.size} onChange={(e) => setQuery({ size: Number(e.target.value), page: 0 })}>
            {PAGE_SIZES.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>
        <Button variant="ghost" disabled={query.page === 0 || page.isFetching} onClick={() => setQuery({ page: query.page - 1 })}>‹ Previous</Button>
        <Button variant="ghost" disabled={!page.data?.hasNext || page.isFetching} onClick={() => setQuery({ page: query.page + 1 })}>Next ›</Button>
      </nav>
      {formDrawer}
      {caseDrawer}
    </Panel>
  );
}
