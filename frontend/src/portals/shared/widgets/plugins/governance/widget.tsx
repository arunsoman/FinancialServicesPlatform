/**
 * plugins.governance — platform plugin governance (GAP-4): registered types, and per
 * organization the installations with their readiness gates. Register / install are ONE STEP
 * (AGENTS.md §2.1 easy install): no approval — registering installs it on the platform root.
 * Selected organization lives in the URL (?org=).
 */
import { useCallback, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useWidgetRuntime } from '../../core/runtime';
import { Alert, Button, PageHeader, Panel } from '../../../ui/atoms';
import { NoticeBar } from '../../kit/Notice';
import type { Notice } from '../../kit/Notice';
import { SelectField } from '../../kit/SelectField';
import { StatePanel } from '../../kit/StatePanel';
import { isForbidden, toProblem } from '../../kit/problem';
import { InstallationList } from './InstallationList';
import { PluginTypesTable } from './PluginTypesTable';
import { RegisterPluginForm } from './RegisterPluginForm';
import { useInstallations, useOrgs, usePluginCommand, useTypes } from './model';
import type { PluginCommand } from './model';

export function PluginsGovernance() {
  const { port, context } = useWidgetRuntime();
  const rootId = context.orgId ?? '';
  const [params, setParams] = useSearchParams();
  const types = useTypes(port);
  const orgs = useOrgs(port, rootId);
  const orgId = params.get('org') ?? '';
  const installs = useInstallations(port, orgId);
  const cmd = usePluginCommand(port);
  const [registering, setRegistering] = useState(false);
  const [installType, setInstallType] = useState('');
  const [notice, setNotice] = useState<Notice | null>(null);
  const dismiss = useCallback(() => setNotice(null), []);

  const run = (c: PluginCommand, text: string) => cmd.mutate(c, {
    onSuccess: () => {
      setNotice({ id: Date.now(), kind: 'ok', text });
      setRegistering(false);
      setInstallType('');
    },
  });

  if (types.isPending) return <StatePanel kind="loading" title="Loading plugins" />;
  if (types.isError) {
    return isForbidden(types.error)
      ? <StatePanel kind="forbidden" title="Not allowed here" hint="Plugin governance needs the plugin.manage permission." />
      : <StatePanel kind="error" title="Couldn’t load plugins" hint={toProblem(types.error).message} onRetry={() => void types.refetch()} />;
  }
  const registered = (types.data ?? []).filter((t) => t.status === 'REGISTERED');
  const installedCodes = new Set((installs.data ?? []).map((i) => i.pluginCode));
  const installable = registered.filter((t) => !installedCodes.has(t.code));

  return (
    <div className="grid gap-4">
      <Panel className="p-4">
        <PageHeader
          title="Plugin types"
          subtitle="One step: registering a plugin installs it for every organization; it is ready in about 30 seconds."
          actions={!registering ? <Button variant="primary" data-action="plugin.register" onClick={() => { cmd.reset(); setRegistering(true); }}>Register plugin</Button> : undefined}
        />
        <NoticeBar notice={notice} onDismiss={dismiss} />
        {cmd.isError && <div className="mb-3"><Alert kind="bad">{toProblem(cmd.error).message}</Alert></div>}
        {registering && (
          <div className="mb-4">
            <RegisterPluginForm busy={cmd.isPending} onCancel={() => setRegistering(false)}
              onSubmit={(v) => run({ kind: 'register', ...v }, `${v.code} ${v.version} registered and installed — it reports ready in about 30 seconds.`)} />
          </div>
        )}
        {(types.data ?? []).length === 0
          ? <StatePanel kind="empty" title="No plugin types yet" hint="Register a plugin to make it installable." />
          : <PluginTypesTable types={types.data ?? []} />}
      </Panel>

      <Panel className="p-4">
        <PageHeader title="Installations" subtitle="Which organizations run which plugin, and whether each is ready." />
        <div className="grid gap-3 md:grid-cols-[320px_1fr]">
          <div className="grid content-start gap-3">
            <SelectField
              label="Organization"
              value={orgId}
              placeholder={orgs.isPending ? 'Loading…' : 'Choose an organization'}
              options={(orgs.data ?? []).map((o) => ({ value: o.id, label: o.legalName ?? o.id }))}
              onChange={(e) => setParams((p) => {
                const n = new URLSearchParams(p);
                if (e.target.value) n.set('org', e.target.value);
                else n.delete('org');
                return n;
              }, { replace: true })}
            />
            {orgId && installable.length > 0 && (
              <div className="grid gap-2 rounded-xl border border-line p-3">
                <SelectField
                  label="Install plugin"
                  value={installType}
                  placeholder="Choose a registered plugin"
                  options={installable.map((t) => ({ value: `${t.code}@${t.version}`, label: `${t.code} ${t.version}` }))}
                  onChange={(e) => setInstallType(e.target.value)}
                />
                <Button variant="primary" disabled={!installType || cmd.isPending} data-action="plugin.install" onClick={() => {
                  const [code, version] = installType.split('@');
                  run({ kind: 'install', orgId, code: code ?? '', version: version ?? '' },
                    `${code} installed — it reports ready in about 30 seconds.`);
                }}>
                  {cmd.isPending ? 'Sending…' : 'Install'}
                </Button>
              </div>
            )}
          </div>
          <div className="min-w-0">
            {!orgId ? <StatePanel kind="empty" title="Choose an organization" hint="Its plugin installations and readiness gates appear here." />
              : installs.isPending ? <StatePanel kind="loading" title="Loading installations" />
              : installs.isError ? <StatePanel kind="error" title="Couldn’t load installations" hint={toProblem(installs.error).message} onRetry={() => void installs.refetch()} />
              : (installs.data ?? []).length === 0 ? <StatePanel kind="empty" title="No plugins installed" />
              : <InstallationList installations={installs.data ?? []} />}
          </div>
        </div>
      </Panel>
    </div>
  );
}
