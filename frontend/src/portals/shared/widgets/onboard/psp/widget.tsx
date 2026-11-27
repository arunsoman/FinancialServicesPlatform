/**
 * onboard.psp — the platform onboards PSP (manufacturer) organizations directly; each is born
 * with an administrator and a designated checker. Plugins (e.g. KYC) are then installed from
 * the Plugins screen (four-eyes).
 */
import { useCallback, useState } from 'react';
import { format, parseISO } from 'date-fns';
import { useWidgetRuntime } from '../../core/runtime';
import { Alert, Button, Chip, PageHeader, Panel, StatusBadge } from '../../../ui/atoms';
import { NoticeBar } from '../../kit/Notice';
import type { Notice } from '../../kit/Notice';
import { StatePanel } from '../../kit/StatePanel';
import { isForbidden, toProblem } from '../../kit/problem';
import { can as canAny, useSession } from '../../../web-core/session-core';
import { readProblem } from '../../../web-core/api';
import { PspForm } from './PspForm';
import { useCreatePsp, usePsps } from './model';
import type { PspCreated } from './model';

export function OnboardPsp() {
  const { port, context } = useWidgetRuntime();
  const rootId = context.orgId ?? '';
  const psps = usePsps(port, rootId);
  const create = useCreatePsp(port, rootId);
  const session = useSession();
  // "sudo into a tenant" (plans/act-as-tenant-plan.md): a platform operator steps into a PSP
  // below their home org and acts there — audited as the real person (C6).
  const maySudo = canAny(session.permissions, ['tenant.act_as']);
  const [actingId, setActingId] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const [created, setCreated] = useState<PspCreated | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const dismiss = useCallback(() => setNotice(null), []);

  if (psps.isPending) return <StatePanel kind="loading" title="Loading organizations" />;
  if (psps.isError) {
    return isForbidden(psps.error)
      ? <StatePanel kind="forbidden" title="Not allowed here" hint="Onboarding PSPs needs org.create at the platform root." />
      : <StatePanel kind="error" title="Couldn’t load organizations" hint={toProblem(psps.error).message} onRetry={() => void psps.refetch()} />;
  }
  return (
    <Panel className="p-4">
      <PageHeader title="PSP organizations" subtitle="Manufacturers onboarded by the platform."
        actions={!open ? <Button variant="primary" data-action="org.create" onClick={() => { create.reset(); setCreated(null); setOpen(true); }}>New organization</Button> : undefined} />
      <NoticeBar notice={notice} onDismiss={dismiss} />
      {created && (
        <div className="mb-3">
          <Alert kind="ok">
            Created. Administrator <span className="mono">{created.adminUsername}</span>, checker{' '}
            <span className="mono">{created.checkerUsername}</span>. {created.note}
          </Alert>
        </div>
      )}
      {open && (
        <div className="mb-4">
          <PspForm busy={create.isPending} problem={create.isError ? toProblem(create.error).message : null}
            onCancel={() => setOpen(false)}
            onSubmit={(v) => create.mutate(v, {
              onSuccess: (res) => {
                setCreated(res);
                setOpen(false);
                setNotice({ id: Date.now(), kind: 'ok', text: `${v.legalName} created.` });
              },
            })} />
        </div>
      )}
      {(psps.data ?? []).length === 0 ? <StatePanel kind="empty" title="No PSP organizations yet" /> : (
        <ul className="grid gap-2">
          {(psps.data ?? []).map((p) => (
            <li key={p.id} className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-line px-3 py-2">
              <div className="min-w-0">
                <div className="truncate text-sm font-semibold">{p.legalName ?? p.id}</div>
                <div className="text-dim text-[12px]">Since {(() => { try { return format(parseISO(p.createdAt), 'd MMM yyyy'); } catch { return p.createdAt; } })()}</div>
              </div>
              <div className="flex gap-1.5">
                <Chip tone="accent">{p.subType ?? p.type}</Chip>
                <StatusBadge status={p.status} />
                {maySudo && p.status === 'ACTIVE' && (
                  <Button
                    variant="ghost"
                    data-action="act-as"
                    disabled={actingId !== null}
                    aria-label={`Act as ${p.legalName ?? p.id}`}
                    onClick={async () => {
                      setActingId(p.id);
                      setNotice(null);
                      try {
                        await session.startActAs(p.id);
                        setNotice({ id: Date.now(), kind: 'ok', text: `Acting as ${p.legalName ?? 'this organization'} — see the banner; every action is audited as you.` });
                      } catch (e) {
                        const pd = readProblem(e);
                        setNotice({ id: Date.now(), kind: 'bad', text: `Act as refused: ${pd.detail}` });
                      } finally {
                        setActingId(null);
                      }
                    }}
                  >
                    Act as
                  </Button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}
