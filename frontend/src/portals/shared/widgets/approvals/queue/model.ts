/**
 * approvals.queue — wire types, labels and hooks. The server decides `canDecide` per caller
 * (permission + checker flag + four-eyes); the UI only renders it, never re-derives it.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { WidgetDataPortLike } from '../../core/manifest';

export interface QueueRow {
  id: string;
  tenantId: string;
  operationType: string;
  targetResourceId: string | null;
  status: 'PENDING_CHECKER' | 'APPROVED' | 'REJECTED';
  makerId: string;
  makerName: string | null;
  checkerId: string | null;
  checkerName: string | null;
  reason: string | null;
  requestedAt: string;
  decidedAt: string | null;
  payload: string | null;
  canDecide: boolean;
  blockedReason: 'OWN_REQUEST' | 'NOT_CHECKER' | 'NO_PERMISSION' | 'PLUGIN_OWNED' | 'DECIDED' | null;
  checkerPermission: string | null;
}

export type StatusFilter = 'PENDING_CHECKER' | 'ALL';

const OP_LABEL: Record<string, string> = {
  USER_CREATE: 'New user',
  USER_UPDATE: 'User change',
  USER_DEACTIVATE: 'Deactivate user',
  USER_ACTIVATE: 'Reactivate user',
  USER_REMOVE: 'Remove user',
  ROLE_CREATE: 'New role',
  ROLE_UPDATE: 'Role permission change',
  ROLE_DELETE: 'Delete role',
  JE_POSTING: 'Journal entry posting',
  BANK_ACCOUNT_CREATE: 'New settlement bank account',
  INVOICE_VOID: 'Void an issued invoice',
  KYC_TENANT_ACTIVATION: 'Organization activation',
  PLUGIN_REGISTER: 'Plugin registration',
  PLUGIN_INSTALL: 'Plugin installation',
  UI_LAYOUT_PUBLISH: 'Portal layout publication',
};

export function opLabel(op: string): string {
  if (op.startsWith('PLUGIN:')) {
    const [, code, action] = op.split(':');
    return `${(code ?? '').toUpperCase()} · ${(action ?? '').replace(/_/g, ' ').toLowerCase()}`;
  }
  return OP_LABEL[op] ?? op.replace(/_/g, ' ').toLowerCase();
}

/** Where the request's subject lives, for "open the record" links (portal-relative). */
export function subjectLink(row: QueueRow): { to: string; label: string } | null {
  const t = row.targetResourceId;
  if (row.operationType.startsWith('PLUGIN:kyc:')) return { to: '/kyc/reviews', label: 'Decide in the KYC review queue' };
  if (!t) return null;
  if (row.operationType.startsWith('USER_')) return { to: `/users?user=${t}`, label: 'Open the user' };
  if (row.operationType === 'ROLE_UPDATE' || row.operationType === 'ROLE_DELETE') return { to: `/rbac?role=${t}`, label: 'Open the role' };
  if (row.operationType === 'JE_POSTING') return { to: `/journal?entry=${t}`, label: 'Open the journal entry' };
  if (row.operationType === 'BANK_ACCOUNT_CREATE') return { to: `/bank-accounts?account=${t}`, label: 'Open the bank accounts' };
  return null;
}

/** Plain-language refusal per server `blockedReason` (ui-std §5.2: explicit, never hidden). */
export function blockedText(row: QueueRow): string | null {
  switch (row.blockedReason) {
    case 'OWN_REQUEST': return 'This is your own request — a different checker must decide it (four-eyes).';
    case 'NOT_CHECKER': return 'You are not enabled as a checker. An administrator can set the checker flag on your user.';
    case 'NO_PERMISSION': return `Deciding this needs the ${row.checkerPermission ?? 'checker'} permission.`;
    case 'PLUGIN_OWNED': return 'This request is decided inside its plugin’s own screen.';
    default: return null;
  }
}

/** The maker's proposal as label/value pairs (core USER_* / ROLE_* only; plugin rows carry none). */
export function proposalLines(payload: string | null): Array<[string, string]> {
  if (!payload) return [];
  try {
    const obj = JSON.parse(payload) as Record<string, unknown>;
    return Object.entries(obj)
      .filter(([k]) => k !== 'baseVersion')
      .map(([k, v]) => [
        k.replace(/([A-Z])/g, ' $1').replace(/^./, (c) => c.toUpperCase()),
        Array.isArray(v) ? (v.length ? v.map(String).join(', ') : '—') : v === null || v === '' ? '—' : String(v),
      ]);
  } catch {
    return [];
  }
}

export const approvalKeys = {
  all: ['approvals'] as const,
  list: (orgId: string, status: StatusFilter) => ['approvals', orgId, 'list', status] as const,
};

export function useQueue(port: WidgetDataPortLike, orgId: string, status: StatusFilter) {
  return useQuery({
    queryKey: approvalKeys.list(orgId, status),
    queryFn: () => port.request<QueueRow[]>('approvals.queue.list', {
      tenantId: orgId, size: '100', ...(status === 'ALL' ? {} : { status }),
    }),
    enabled: orgId !== '',
    placeholderData: (prev) => prev,
  });
}

export interface Decision {
  id: string;
  approve: boolean;
  reason: string;
}

/** Zero retries (ui-std §7): a decision is never silently re-sent. */
export function useDecide(port: WidgetDataPortLike, orgId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (d: Decision) => port.command<unknown>(d.approve ? 'approvals.queue.approve' : 'approvals.queue.reject',
      { reason: d.reason }, { id: d.id }),
    retry: 0,
    onSettled: () => {
      // a decision changes the queue, and the subject's own screen (users/roles/journal)
      void qc.invalidateQueries({ queryKey: approvalKeys.all });
      void qc.invalidateQueries({ queryKey: ['users', 'org', orgId] });
      void qc.invalidateQueries({ queryKey: ['rbac', orgId] });
      void qc.invalidateQueries({ queryKey: ['journal', orgId] });
    },
  });
}
