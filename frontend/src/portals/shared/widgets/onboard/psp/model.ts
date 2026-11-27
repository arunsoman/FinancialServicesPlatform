/** onboard.psp — PSP creation (platform root only; core TenantService.createPsp). */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import type { WidgetDataPortLike } from '../../core/manifest';

export interface PspRow {
  id: string;
  legalName: string | null;
  type: string;
  subType: string | null;
  status: string;
  verificationStatus: string;
  createdAt: string;
}

export interface PspCreated {
  tenantId: string;
  adminUsername: string;
  checkerUsername: string;
  note: string;
}

const username = z.string().trim().toLowerCase()
  .regex(/^[a-z0-9][a-z0-9._-]{2,63}$/, '3–64 characters: lowercase letters, digits, . _ or -');

export const pspSchema = z.object({
  legalName: z.string().trim().min(2, 'Enter the registered legal name').max(200),
  adminUsername: username,
  adminFullName: z.string().trim().min(2, 'Enter the administrator’s name').max(120),
  checkerUsername: username,
  checkerFullName: z.string().trim().min(2, 'Enter the checker’s name').max(120),
}).refine((v) => v.adminUsername !== v.checkerUsername, {
  path: ['checkerUsername'], message: 'The checker must be a different person (four-eyes)',
});
export type PspValues = z.infer<typeof pspSchema>;

export const pspKeys = { list: (rootId: string) => ['onboard', 'psp', rootId] as const };

export function usePsps(port: WidgetDataPortLike, rootId: string) {
  return useQuery({
    queryKey: pspKeys.list(rootId),
    queryFn: () => port.request<PspRow[]>('onboard.psp.list', { tenantId: rootId }),
    enabled: rootId !== '',
  });
}

export function useCreatePsp(port: WidgetDataPortLike, rootId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: PspValues) => port.command<PspCreated>('onboard.psp.create', v),
    retry: 0,
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: pspKeys.list(rootId) });
      void qc.invalidateQueries({ queryKey: ['plugins'] });
    },
  });
}
