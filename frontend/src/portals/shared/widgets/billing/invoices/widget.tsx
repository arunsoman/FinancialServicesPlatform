/**
 * billing.invoices — sales and purchase invoices with 30-day credit terms by default and built-in
 * payment (e-money wallet, bank transfer, card via the PSP's hosted page). Org portals see
 * Sales (they issued) and Purchases (they received); the platform portal sees everything, read-only.
 * URL state: ?idir= tab, ?ist= status filter, ?iq= search, ?ipg= page, ?inv= open invoice, ?inew=1 form.
 * Wide screens: list + detail side by side; phones: the detail replaces the list (Back returns).
 */
import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Button, Input, PageHeader, Panel } from '../../../ui/atoms';
import { useWidgetRuntime } from '../../core/runtime';
import { NoticeBar } from '../../kit/Notice';
import type { Notice } from '../../kit/Notice';
import { Pager } from '../../kit/Pager';
import { StatePanel } from '../../kit/StatePanel';
import { isForbidden, toProblem } from '../../kit/problem';
import { InvoiceDetail } from './InvoiceDetail';
import { InvoiceForm } from './InvoiceForm';
import { InvoiceList } from './InvoiceList';
import { TotalsBar } from './TotalsBar';
import {
  FILTER_LABEL, filtersFor, todayIso, useCounterparties, useInvoice, useInvoiceCommand, useInvoices, usePaymentOptions, useTotals,
} from './model';
import type { Direction, InvoiceCommand, InvoiceValues, InvoiceView, PaymentView, StatusFilter } from './model';

const SIZES = [20, 50] as const;

export function Invoices() {
  const { port, context } = useWidgetRuntime();
  const orgId = context.orgId ?? '';
  // the host passes the signed-in tenant type (PLATFORM for the admin console)
  const platform = context.portal.toUpperCase() === 'PLATFORM';
  const held = context.permissions;
  const [params, setParams] = useSearchParams();
  const patch = useCallback((next: Record<string, string | null>) => setParams((p) => {
    const n = new URLSearchParams(p);
    for (const [k, val] of Object.entries(next)) {
      if (val === null || val === '') n.delete(k);
      else n.set(k, val);
    }
    return n;
  }, { replace: true }), [setParams]);

  const direction: Direction = platform ? 'ALL' : params.get('idir') === 'PURCHASES' ? 'PURCHASES' : 'SALES';
  const filters = filtersFor(direction);
  const st = params.get('ist') as StatusFilter | null;
  const status: StatusFilter = st && filters.includes(st) ? st : 'ALL';
  const page = Math.max(0, Number(params.get('ipg') ?? '0') || 0);
  const [size, setSize] = useState<number>(SIZES[0]);
  const invoiceId = params.get('inv');
  const creating = params.get('inew') === '1' && !platform;
  const [search, setSearch] = useState(params.get('iq') ?? '');
  const today = todayIso();

  // server-side search: debounce typing into the URL (which drives the query)
  useEffect(() => {
    const t = setTimeout(() => { if ((params.get('iq') ?? '') !== search.trim()) patch({ iq: search.trim() || null, ipg: null }); }, 300);
    return () => clearTimeout(t);
  }, [search, params, patch]);

  const list = useInvoices(port, orgId, { direction, status, q: params.get('iq') ?? '', page, size });
  const totals = useTotals(port, orgId, direction);
  const narrow = context.tier === 'xs' || context.tier === 'sm';
  // wide screens are master-detail: with nothing picked, the first invoice is shown
  const shownId = invoiceId ?? (narrow ? null : list.data?.items.find((i) => i.invoiceId)?.invoiceId ?? null);
  const invoice = useInvoice(port, orgId, shownId);
  const [paying, setPaying] = useState(false);
  const options = usePaymentOptions(port, orgId, shownId, paying);
  const buyers = useCounterparties(port, orgId, creating);
  const cmd = useInvoiceCommand(port, orgId);
  const [notice, setNotice] = useState<Notice | null>(null);
  const dismiss = useCallback(() => setNotice(null), []);
  const say = (kind: Notice['kind'], text: string) => setNotice({ id: Date.now(), kind, text });

  const run = (c: InvoiceCommand, after?: (res: InvoiceView | PaymentView) => void) => cmd.mutate(c, {
    onSuccess: (res) => {
      if (after) { after(res); return; }
      if (c.kind === 'issue') say('ok', 'Issued — the buyer can see and pay it now.');
      if (c.kind === 'confirm') say('ok', 'Payment confirmed — the balance is updated.');
      if (c.kind === 'void') say('ok', (res as InvoiceView).status === 'VOID' ? 'Draft discarded.' : 'Void requested — a second person approves it.');
      if (c.kind === 'pay') {
        const p = res as PaymentView;
        setPaying(false);
        if (p.status === 'SUCCEEDED') say('ok', 'Paid — the invoice balance is updated.');
        else if (p.status === 'AWAITING_CONFIRMATION') say('ok', 'Transfer recorded — the seller confirms when the money arrives.');
        else if (p.status === 'PENDING') say('ok', 'Finish the card payment on the secure card page.');
        else if (p.status === 'FAILED') say('bad', 'The payment failed — nothing was taken. Try another option.');
        else say('bad', 'The payment provider did not answer — we are checking; do not pay again yet.');
      }
    },
  });

  const create = (values: InvoiceValues, issue: boolean) =>
    run({ kind: 'create', values }, (res) => {
      const inv = res as InvoiceView;
      patch({ inew: null, inv: inv.invoiceId });
      if (issue) run({ kind: 'issue', invoiceId: inv.invoiceId, version: inv.version });
      else say('ok', 'Draft saved — issue it when you are ready.');
    });

  if (list.isPending) return <StatePanel kind="loading" title="Loading invoices" />;
  if (list.isError) {
    return isForbidden(list.error)
      ? <StatePanel kind="forbidden" title="Not allowed here" hint="Invoices need invoice.view." />
      : <StatePanel kind="error" title="Couldn’t load invoices" hint={toProblem(list.error).message} onRetry={() => void list.refetch()} />;
  }
  const pageData = list.data;
  const problem = cmd.isError ? toProblem(cmd.error).message : null;
  const canCreate = !platform && direction === 'SALES' && held.includes('invoice.create');
  const tabs: { id: Direction; label: string }[] = platform ? [] : [{ id: 'SALES', label: 'Sales' }, { id: 'PURCHASES', label: 'Purchases' }];
  const close = () => { cmd.reset(); setPaying(false); patch({ inv: null }); };

  return (
    <Panel className="p-4">
      <PageHeader
        title="Invoices"
        subtitle={platform ? 'Every invoice on the platform (read-only).' : 'Issue on 30-day credit, get paid by wallet, bank transfer or card.'}
        actions={canCreate ? (
          <Button variant="primary" data-action="invoice.create" onClick={() => { cmd.reset(); patch({ inew: '1', inv: null }); }}>New invoice</Button>
        ) : undefined}
      />
      <NoticeBar notice={notice} onDismiss={dismiss} />

      {tabs.length > 0 && (
        <div role="tablist" aria-label="Sales or purchases" className="mb-3 flex gap-1">
          {tabs.map((t) => (
            <button key={t.id} type="button" role="tab" aria-selected={direction === t.id}
              className={direction === t.id ? 'fsp-chip fsp-chip-accent' : 'fsp-chip'}
              onClick={() => patch({ idir: t.id === 'SALES' ? null : t.id, ist: null, ipg: null, inv: null })}>
              {t.label}
            </button>
          ))}
        </div>
      )}

      {totals.data && (
        <TotalsBar totals={totals.data} outstandingLabel={direction === 'SALES' ? 'Owed to you' : direction === 'PURCHASES' ? 'You owe' : 'Outstanding'} />
      )}

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <div role="tablist" aria-label="Filter by status" className="flex flex-wrap gap-1">
          {filters.map((f) => (
            <button key={f} type="button" role="tab" aria-selected={status === f}
              className={status === f ? 'fsp-chip fsp-chip-accent' : 'fsp-chip'} onClick={() => patch({ ist: f === 'ALL' ? null : f, ipg: null })}>
              {FILTER_LABEL[f]}
            </button>
          ))}
        </div>
        <span className="grow" />
        <Input type="search" aria-label="Search invoices" placeholder="Search number, organisation, reference"
          className={narrow ? 'w-full' : 'w-72'} value={search} onChange={(e) => setSearch(e.target.value)} />
      </div>

      {creating && (
        <section className="mb-4 rounded-xl border border-line-strong bg-raised p-3" aria-label="New invoice">
          <h3 className="mb-2 text-[15px] font-bold">New invoice <span className="text-dim text-[12px] font-normal">· 30-day credit unless you choose other terms</span></h3>
          <InvoiceForm buyers={buyers.data ?? []} buyersLoading={buyers.isPending} today={today} busy={cmd.isPending} problem={problem}
            onCancel={() => { cmd.reset(); patch({ inew: null }); }} onSubmit={create} />
        </section>
      )}

      <div className={narrow ? 'grid grid-cols-[minmax(0,1fr)] gap-4' : 'grid grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)] gap-4'}>
        {(!narrow || !invoiceId) && (
          <div className="min-w-0">
            {pageData.items.length === 0 ? (
              <StatePanel kind="empty" title={params.get('iq') ? 'No invoices match' : 'No invoices here yet'}
                hint={canCreate && status === 'ALL' && !params.get('iq') ? 'Create your first invoice — it is on 30-day terms by default.' : 'Try another filter.'} />
            ) : (
              <InvoiceList invoices={pageData.items} direction={direction} today={today} selectedId={shownId}
                onSelect={(id) => { cmd.reset(); setPaying(false); patch({ inv: id, inew: null }); }} />
            )}
            <Pager page={pageData.page} size={size} shown={pageData.items.length} totalElements={pageData.totalElements} hasNext={pageData.hasNext}
              sizes={SIZES} busy={list.isFetching} noun="invoices" onPage={(n) => patch({ ipg: n ? String(n) : null })}
              onSize={(s) => { setSize(s); patch({ ipg: null }); }} />
          </div>
        )}
        {(!narrow || invoiceId) && (
          <section aria-label="Invoice" className="min-w-0">
            {narrow && <Button variant="ghost" className="mb-2" onClick={close}>← All invoices</Button>}
            {!shownId ? <StatePanel kind="empty" title="Select an invoice" hint="Its lines, payments and what you can do next show here." />
              : invoice.isPending ? <StatePanel kind="loading" title="Loading invoice" />
                : invoice.isError ? <StatePanel kind="error" title="Couldn’t load the invoice" hint={toProblem(invoice.error).message} onRetry={() => void invoice.refetch()} />
                  : (
                    <div className="rounded-xl border border-line p-3">
                      <h3 className="mb-1 text-[15px] font-bold">{invoice.data.number ?? 'Draft invoice'}</h3>
                      <p className="text-dim mb-3 text-[12px]">{invoice.data.seller.name} → {invoice.data.buyer.name}</p>
                      <InvoiceDetail invoice={invoice.data} me={{ orgId, held, platform }} today={today}
                        options={options.data ?? []} optionsLoading={options.isPending} paying={paying}
                        onStartPay={() => { cmd.reset(); setPaying(true); }} onStopPay={() => setPaying(false)}
                        busy={cmd.isPending} problem={problem}
                        onIssue={() => run({ kind: 'issue', invoiceId: invoice.data.invoiceId, version: invoice.data.version })}
                        onPay={(method, amountMinor) => run({ kind: 'pay', invoiceId: invoice.data.invoiceId, version: invoice.data.version, method, amountMinor })}
                        onConfirm={(paymentId) => run({ kind: 'confirm', invoiceId: invoice.data.invoiceId, paymentId })}
                        onVoid={(reason) => run({ kind: 'void', invoiceId: invoice.data.invoiceId, version: invoice.data.version, reason })} />
                    </div>
                  )}
          </section>
        )}
      </div>
    </Panel>
  );
}
