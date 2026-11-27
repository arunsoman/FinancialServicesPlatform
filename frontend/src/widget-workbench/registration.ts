/**
 * Registering a widget with core's widget registry (PUT /api/ui/widgets — operation
 * ui.widget.register, re-checked by core on every call). Shared by the registry table and
 * the widget page's "Register" button. Registration is direct: one person with
 * ui.widget.register puts the widget in the system — no second approver.
 *
 * The browser only ever talks to the BFF (session cookie + CSRF + Idempotency-Key via apiPut);
 * no token reaches the page (FAPI 2.0).
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiGet, apiPut } from '../portals/shared/web-core/api';
import type { WidgetDef } from '../portals/shared/widgets/core/manifest';
import { useConnection } from './connection';
import { registration } from './model';

/** core UiDtos.WidgetView */
export interface ServerWidget {
  id: string;
  version: string;
  label: string;
  category: string | null;
  bundle: string;
  portals: string[];
  requires: string[];
  bundleSha256: string | null;
  status: string;
  updatedAt: string;
  /** first registration — a re-registration never moves it */
  createdAt: string | null;
}

export const SERVER_WIDGETS_KEY = ['ui', 'widgets', 'server'] as const;

/** What core's registry holds (only while signed in). */
export function useServerWidgets() {
  const conn = useConnection();
  return useQuery({
    queryKey: SERVER_WIDGETS_KEY,
    queryFn: () => apiGet<ServerWidget[]>('/api/ui/widgets'),
    enabled: conn.status === 'connected',
  });
}

/** Register (or re-register at a new version) one widget. Never retried automatically. */
export function useRegisterWidget() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (def: WidgetDef) => apiPut<ServerWidget>('/api/ui/widgets', registration(def)),
    retry: 0,
    onSettled: () => qc.invalidateQueries({ queryKey: SERVER_WIDGETS_KEY }),
  });
}

export type RegistrationState =
  | { kind: 'signed-out' }
  | { kind: 'loading' }
  | { kind: 'unavailable'; detail: string }
  | { kind: 'registered'; version: string }
  | { kind: 'outdated'; serverVersion: string }
  | { kind: 'missing' };

/** Where this build's widget stands against core's registry. */
export function registrationState(
  def: Pick<WidgetDef, 'id' | 'version'>,
  connected: boolean,
  server: { data?: readonly Pick<ServerWidget, 'id' | 'version'>[]; isLoading: boolean; error: unknown; detail?: string },
): RegistrationState {
  if (!connected) return { kind: 'signed-out' };
  if (server.error) return { kind: 'unavailable', detail: server.detail ?? 'The registry did not answer.' };
  if (server.isLoading || !server.data) return { kind: 'loading' };
  const held = server.data.find((w) => w.id === def.id);
  if (!held) return { kind: 'missing' };
  return held.version === def.version ? { kind: 'registered', version: held.version } : { kind: 'outdated', serverVersion: held.version };
}
