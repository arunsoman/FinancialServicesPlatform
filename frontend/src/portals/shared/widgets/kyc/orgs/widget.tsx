/**
 * kyc.orgs component — the organization tree with KYC status (plans §6.3).
 * Org portals see their own node + subtree (server-enforced reachability); the
 * admin lane sees what it can reach. Pure read widget (four-operation mapping).
 */
import { useNavigate } from 'react-router-dom';
import { useWidgetRuntime, usePortData } from '../../core/runtime';
import { Chip, EmptyState, Panel, PageHeader, StatusBadge } from '../../../ui/atoms';
import type { TenantSummary } from '../../../web-core/session-core';

/** Route of the KYC review/manage screen (the "KYC" screen) — org portals only. */
const KYC_SCREEN = '/kyc/reviews';

/**
 * The admin console has no KYC screen: there a pick selects the org for the rest of the page
 * (its users sit beside this list). Org portals keep opening the org's KYC screen.
 */
const pickStaysOnPage = (portal: string) => portal === 'PLATFORM';

interface OrgRow extends Partial<TenantSummary> {
  id: string;
  legalName: string;
  subType: string | null;
  status: string;
  verificationStatus?: string;
}

export function KycOrgs(props: { mode: 'live' | 'fixture'; state: string }) {
  const { port, context } = useWidgetRuntime();
  const navigate = useNavigate();
  const stay = pickStaysOnPage(context.portal);
  const picked = context.selection.orgId ?? null;
  const self = usePortData<OrgRow>(() => port.request('kyc.orgs.self'), [context.orgId]);
  const children = usePortData<OrgRow[]>(() => port.request('kyc.orgs.tree'), [context.orgId]);

  const rows: OrgRow[] =
    self.data && children.data
      ? [self.data, ...(Array.isArray(children.data) ? children.data : [])]
      : [];

  if (self.state === 'loading' || children.state === 'loading') {
    return (
      <Panel className="p-4">
        <EmptyState title="Loading organizations…" />
      </Panel>
    );
  }
  if (children.state === 'forbidden') {
    return <Panel className="p-4"><EmptyState title="Not allowed here" hint="You lack the KYC review permission for this subtree." /></Panel>;
  }
  if (rows.length === 0) {
    return (
      <Panel className="p-4">
        <EmptyState title="No organizations" hint="Onboard one from the Onboarding screen." />
      </Panel>
    );
  }
  if (children.state === 'error' || self.state === 'error') {
    return <Panel className="p-4"><EmptyState title="Could not load organizations" hint={children.problem?.detail ?? self.problem?.detail} /></Panel>;
  }

  return (
    <Panel className="p-4">
      <PageHeader title="Organizations" subtitle={`${rows.length} around you`} />
      <ul className="flex flex-col divide-y divide-line">
        {rows.map((o) => (
          <li key={o.id} className={`flex items-center gap-3 py-2 ${stay && picked === o.id ? 'rounded-lg bg-raised px-2' : ''}`}>
            <button
              type="button"
              className="min-w-0 flex-1 text-left"
              aria-pressed={stay ? picked === o.id : undefined}
              onClick={() => {
                context.select('orgId', o.id);
                if (!stay) navigate(KYC_SCREEN);
              }}
            >
              <div className="truncate text-sm font-semibold">{o.legalName ?? o.id}</div>
              <div className="text-dim mono text-[10px]">{o.id.slice(0, 8)}…</div>
            </button>
            <Chip tone="accent">{o.subType ?? o.type ?? ''}</Chip>
            <StatusBadge status={o.status} />
          </li>
        ))}
      </ul>
      <p className="text-dim mt-3 text-xs">
        {props.mode === 'fixture' ? 'fixture lane · ' : ''}{stay ? 'pick one to see its users' : 'pick one to open its KYC screen'}
      </p>
    </Panel>
  );
}
