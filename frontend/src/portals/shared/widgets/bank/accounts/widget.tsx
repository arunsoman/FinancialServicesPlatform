/** bank.accounts — settlement bank accounts of the organization (F-10). */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import type { FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Alert, Button, Chip, PageHeader, Panel } from '../../../ui/atoms';
import { StatePanel } from '../../kit/StatePanel';
import { toProblem } from '../../kit/problem';
import { useWidgetRuntime } from '../../core/runtime';
import { BANKS, bankKeys, STATUS_TONE } from './model';
import type { BankAccountsResponse } from './model';

export function BankAccountsWidget() {
  const { port, context } = useWidgetRuntime();
  const orgId = context.orgId ?? '';
  const [params, setParams] = useSearchParams();
  const accounts = useQuery({
    queryKey: bankKeys(orgId).root,
    queryFn: () => port.request<BankAccountsResponse>('bank.accounts.list', { tenantId: orgId }),
    enabled: !!orgId,
    retry: 1,
  });
  // one-open-form-at-a-time lives in the URL (?file=1) — refresh-safe, shareable (ui-std §7);
  // the form itself gates on the create permission, so a checker never sees it
  const filing = params.get('file') === '1';
  const open = (on: boolean) => setParams((p) => {
    const n = new URLSearchParams(p);
    if (on) n.set('file', '1'); else n.delete('file');
    return n;
  }, { replace: true });

  if (accounts.isPending) return <StatePanel kind="loading" title="Loading bank accounts" />;
  if (accounts.isError) {
    return <StatePanel kind="error" title="Couldn’t load bank accounts"
      hint={toProblem(accounts.error).message} onRetry={() => void accounts.refetch()} />;
  }

  const rows = accounts.data.items ?? [];
  const canCreate = context.permissions.includes('bank.account.create');
  const pending = rows.find((r) => r.status === 'PENDING');
  const active = rows.find((r) => r.status === 'ACTIVE');

  return (
    <Panel className="p-4">
      <PageHeader
        title="Settlement bank accounts"
        subtitle={active
          ? `Active: ${BANKS[active.bankCode] ?? active.bankCode} · branch ${active.branchCode} · ${active.maskedAccountNumber}`
          : 'No active account yet'}
        actions={canCreate
          ? <Button variant="primary" data-action="bank.account.file" onClick={() => open(!filing)}>
              {filing ? 'Close form' : 'Request settlement account'}
            </Button>
          : undefined}
      />
      {pending && (
        <div className="mb-3" data-testid="bank-pending-banner">
          <Alert kind="warn">
            A settlement account ({BANKS[pending.bankCode] ?? pending.bankCode} · {pending.maskedAccountNumber}, filed
            by {pending.makerName ?? 'a maker'}) is awaiting approval — the organization’s checker decides it in Approvals.
          </Alert>
        </div>
      )}
      {filing && canCreate && <FileForm onDone={() => open(false)} />}
      {rows.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full border-separate border-spacing-0 text-sm">
            <thead>
              <tr className="text-dim text-left text-[11px] uppercase tracking-[.12em]">
                <th className="border-b border-line py-2 pr-3 font-bold">Holder</th>
                <th className="border-b border-line py-2 pr-3 font-bold">Bank</th>
                <th className="border-b border-line py-2 pr-3 font-bold">Branch</th>
                <th className="border-b border-line py-2 pr-3 font-bold">Account</th>
                <th className="border-b border-line py-2 pr-3 font-bold">Type</th>
                <th className="border-b border-line py-2 pr-3 font-bold">Status</th>
                <th className="border-b border-line py-2 font-bold">Decision</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} data-testid={`bank-row-${r.status.toLowerCase()}`}>
                  <td className="border-b border-line py-2 pr-3">{r.holderName}</td>
                  <td className="border-b border-line py-2 pr-3">{BANKS[r.bankCode] ?? r.bankCode}</td>
                  <td className="mono border-b border-line py-2 pr-3 tabular-nums">{r.branchCode}</td>
                  <td className="mono border-b border-line py-2 pr-3 tabular-nums">{r.maskedAccountNumber}</td>
                  <td className="border-b border-line py-2 pr-3 text-[12px]">{r.accountType.toLowerCase()}</td>
                  <td className="border-b border-line py-2 pr-3"><Chip tone={STATUS_TONE[r.status]}>{r.status.toLowerCase()}</Chip></td>
                  <td className="border-b border-line py-2 text-[12px]">
                    {r.status === 'PENDING' ? 'awaiting checker' : r.status === 'ACTIVE' ? '—' : r.status.toLowerCase()}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {rows.length === 0 && !filing && (
        <StatePanel kind="empty" title="No bank accounts yet"
          hint="The organization’s payout destination is filed by its maker and checked before it goes active." />
      )}
    </Panel>
  );
}

interface FileFormProps {
  onDone: () => void;
}

/**
 * Maker-side filing form (ui-std §8: validation on blur/submit, inline hints). The
 * service re-validates everything — it is the source of truth; these checks only shape
 * the input. Never auto-retried (ui-std §7: money-adjacent mutation).
 */
export function FileForm({ onDone }: FileFormProps) {
  const { port, context } = useWidgetRuntime();
  const orgId = context.orgId ?? '';
  const qc = useQueryClient();
  const [holderName, setHolderName] = useState('');
  const [bankCode, setBankCode] = useState('BANK_WINDHOEK');
  const [branchCode, setBranchCode] = useState('');
  const [accountNumber, setAccountNumber] = useState('');
  const [accountType, setAccountType] = useState('SETTLEMENT');
  const branchOk = /^\d{5,6}$/.test(branchCode.trim());
  const accountOk = /^\d{6,16}$/.test(accountNumber.trim());
  const holderOk = holderName.trim().length >= 2 && holderName.trim().length <= 160;
  const create = useMutation({
    mutationFn: () => port.command<void>('bank.accounts.create',
      { holderName: holderName.trim(), bankCode, branchCode: branchCode.trim(),
        accountNumber: accountNumber.trim(), accountType, currency: 'NAD' },
      { tenantId: orgId }),
    retry: 0,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: bankKeys(orgId).root });
      onDone();
    },
  });
  const submit: (e: FormEvent) => void = (e) => {
    e.preventDefault();
    if (holderOk && branchOk && accountOk && bankCode) create.mutate();
  };
  const field = 'border border-line rounded px-2 py-1.5 text-sm w-full';
  return (
    <form className="mb-4 grid gap-3 rounded border border-line p-3" data-testid="bank-file-form"
      onSubmit={submit}>
      <div className="text-[12px] text-dim">
        Files the organization’s settlement account for four-eyes review. The account number is
        shown masked (last 4) everywhere; the checker confirms it against the bank registry.
      </div>
      <label className="grid gap-1 text-[12px]">
        Account holder
        <input className={field} value={holderName} data-testid="bank-holder"
          onChange={(e) => setHolderName(e.target.value)}
          onBlur={() => setHolderName(holderName.trim())}
          aria-invalid={!holderOk}
          placeholder="e.g. Namib Fresh Produce" required />
      </label>
      {!holderOk && <div className="text-[12px]" data-testid="bank-holder-error">2–160 characters</div>}
      <label className="grid gap-1 text-[12px]">
        Bank
        <select className={field} value={bankCode} data-testid="bank-code" onChange={(e) => setBankCode(e.target.value)}>
          {Object.entries(BANKS).map(([code, name]) => <option key={code} value={code}>{name}</option>)}
        </select>
      </label>
      <div className="grid grid-cols-2 gap-3">
        <label className="grid gap-1 text-[12px]">
          Branch code
          <input className={field} value={branchCode} data-testid="bank-branch" inputMode="numeric"
            placeholder="481972" onChange={(e) => setBranchCode(e.target.value)} aria-invalid={!branchOk} />
        </label>
        <label className="grid gap-1 text-[12px]">
          Account number
          <input className={field} value={accountNumber} data-testid="bank-account-number" inputMode="numeric"
            placeholder="12345678" onChange={(e) => setAccountNumber(e.target.value)} aria-invalid={!accountOk} />
        </label>
      </div>
      {!branchOk && branchCode.length > 0 && <div className="text-[12px]" data-testid="bank-branch-error">5–6 digits</div>}
      {!accountOk && accountNumber.length > 0 && <div className="text-[12px]" data-testid="bank-account-error">6–16 digits</div>}
      <label className="grid gap-1 text-[12px]">
        Account type
        <select className={field} value={accountType} data-testid="bank-account-type" onChange={(e) => setAccountType(e.target.value)}>
          <option value="SETTLEMENT">Settlement</option>
          <option value="CURRENT">Current</option>
          <option value="SAVINGS">Savings</option>
        </select>
      </label>
      {create.isError && <Alert kind="bad">{toProblem(create.error).message}</Alert>}
      <div className="flex justify-end gap-2">
        <Button type="button" onClick={onDone}>Cancel</Button>
        <Button type="submit" variant="primary" data-action="bank.account.request"
          disabled={!holderOk || !branchOk || !accountOk || create.isPending}>
          {create.isPending ? 'Submitting…' : 'Submit for review'}
        </Button>
      </div>
    </form>
  );
}
