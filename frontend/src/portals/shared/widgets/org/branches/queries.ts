/**
 * org.branches — server state. Reads retry once (idempotent GETs); writes and decisions are
 * never retried and never optimistic (ui-std §7): the list re-syncs from core after each one.
 * `port.command` already carries the Idempotency-Key and CSRF header.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { WidgetDataPortLike } from '../../core/manifest';
import { branchKeys, createBody, decisionBody, deleteParams, listParams, updateBody } from './model';
import type { BranchChange, BranchFormValues, BranchListQuery, BranchPage, BranchRow } from './model';

export function useBranchList(port: WidgetDataPortLike, orgId: string, q: BranchListQuery) {
  return useQuery({
    queryKey: branchKeys.list(orgId, q),
    queryFn: () => port.request<BranchPage>('org.branches.list', listParams(orgId, q)),
    enabled: orgId !== '',
    retry: 1,
    // keep the current rows while the next page / filter loads — but never one org's under another
    placeholderData: (prev, prevQuery) => (prevQuery?.queryKey[2] === orgId ? prev : undefined),
  });
}

/** The open branch, by id — it may not be on the page shown (a deep link, a narrowed search). */
export function useBranch(port: WidgetDataPortLike, orgId: string, id: string | null, row: BranchRow | undefined) {
  return useQuery({
    queryKey: branchKeys.one(orgId, id ?? ''),
    queryFn: () => port.request<BranchRow>('org.branches.get', { tenantId: orgId, branchId: id ?? '' }),
    enabled: orgId !== '' && Boolean(id) && id !== 'new',
    initialData: row,
    retry: false,
  });
}

export type BranchCommand =
  | { kind: 'create'; values: BranchFormValues }
  | { kind: 'update'; branch: BranchRow; values: BranchFormValues }
  | { kind: 'delete'; branch: BranchRow };

/** Maker writes: each answers 202 with the approval it opened; the branch changes only when decided. */
export function useBranchCommand(port: WidgetDataPortLike, orgId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (cmd: BranchCommand): Promise<BranchChange> => {
      switch (cmd.kind) {
        case 'create':
          return port.command<BranchChange>('org.branches.create', createBody(cmd.values), { tenantId: orgId });
        case 'update':
          return port.command<BranchChange>('org.branches.update', updateBody(cmd.branch, cmd.values),
            { tenantId: orgId, branchId: cmd.branch.id });
        case 'delete':
          return port.command<BranchChange>('org.branches.delete', undefined, deleteParams(orgId, cmd.branch));
      }
    },
    retry: 0,
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: branchKeys.org(orgId) });
      void qc.invalidateQueries({ queryKey: ['approvals'] });
    },
  });
}

export interface Decision {
  approvalId: string;
  approve: boolean;
  reason: string;
}

/** Checker decision through core approvals — core enforces maker ≠ checker on person_id. */
export function useBranchDecision(port: WidgetDataPortLike, orgId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (d: Decision) =>
      port.command<unknown>(d.approve ? 'org.branches.approve' : 'org.branches.reject', decisionBody(d.reason), { id: d.approvalId }),
    retry: 0,
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: branchKeys.org(orgId) });
      void qc.invalidateQueries({ queryKey: ['approvals'] });
    },
  });
}
