/** plugins.governance — types + hooks. Registration and installation are four-eyes (PLUGIN_*). */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import type { WidgetDataPortLike } from '../../core/manifest';

export interface PluginTypeRow {
  id: string;
  code: string;
  version: string;
  name: string;
  baseUrl: string;
  status: 'PENDING_APPROVAL' | 'REGISTERED' | 'SUSPENDED' | 'DEACTIVATED';
  manifestHash: string;
  operationCount: number;
  permissions: string[];
  registeredAt: string;
  approvedAt: string | null;
  pendingApprovalId: string | null;
}

export interface GateRow {
  gate: string;
  status: 'PENDING' | 'ACTIVE' | 'FAILED';
  updatedAt: string;
}

export interface InstallationRow {
  id: string;
  tenantId: string;
  pluginCode: string;
  pluginVersion: string;
  status: string;
  inheritanceOrigin: string | null;
  gates: GateRow[];
  ready: boolean;
  createdAt: string;
  updatedAt: string;
  pendingApprovalId: string | null;
}

export interface OrgRow {
  id: string;
  legalName: string | null;
  type: string;
  subType: string | null;
  status: string;
}

export const registerSchema = z.object({
  baseUrl: z.string().trim().url('Enter the plugin’s base URL, e.g. http://localhost:8083'),
  manifest: z.string().trim().min(2, 'Paste the plugin manifest (JSON)').superRefine((v, ctx) => {
    try {
      const m = JSON.parse(v) as { code?: unknown; version?: unknown };
      if (typeof m.code !== 'string' || typeof m.version !== 'string') {
        ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'The manifest needs "code" and "version"' });
      }
    } catch {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'The manifest is not valid JSON' });
    }
  }),
});
export type RegisterValues = z.infer<typeof registerSchema>;

/** SHA-256 of the manifest exactly as sent — core recomputes and compares. */
export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export const pluginKeys = {
  all: ['plugins'] as const,
  types: ['plugins', 'types'] as const,
  orgs: (rootId: string) => ['plugins', 'orgs', rootId] as const,
  installations: (orgId: string) => ['plugins', 'installations', orgId] as const,
};

export function useTypes(port: WidgetDataPortLike) {
  return useQuery({ queryKey: pluginKeys.types, queryFn: () => port.request<PluginTypeRow[]>('plugins.governance.types') });
}

export function useOrgs(port: WidgetDataPortLike, rootId: string) {
  return useQuery({
    queryKey: pluginKeys.orgs(rootId),
    queryFn: () => port.request<OrgRow[]>('plugins.governance.orgs', { tenantId: rootId }),
    enabled: rootId !== '',
  });
}

export function useInstallations(port: WidgetDataPortLike, orgId: string) {
  return useQuery({
    queryKey: pluginKeys.installations(orgId),
    queryFn: () => port.request<InstallationRow[]>('plugins.governance.installations', { orgId }),
    enabled: orgId !== '',
  });
}

export type PluginCommand =
  | { kind: 'register'; code: string; version: string; baseUrl: string; manifest: string; manifestHash: string }
  | { kind: 'install'; orgId: string; code: string; version: string };

export function usePluginCommand(port: WidgetDataPortLike) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (c: PluginCommand) => c.kind === 'register'
      ? port.command<unknown>('plugins.governance.register',
        { code: c.code, version: c.version, baseUrl: c.baseUrl, manifest: c.manifest, manifestHash: c.manifestHash })
      : port.command<unknown>('plugins.governance.install', { code: c.code, version: c.version, config: null }, { orgId: c.orgId }),
    retry: 0,
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: pluginKeys.all });
      void qc.invalidateQueries({ queryKey: ['approvals'] });
    },
  });
}
