/**
 * users.organization — wire types, validation schema and data hooks.
 * Server state rides TanStack Query (ui-std §7) on top of the widget port, so the
 * ONE network path (port → BFF → gateway → core) is preserved.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { ApiError } from '../../../web-core/api';
import type { WidgetDataPortLike } from '../../core/manifest';
import { PRIMARY_CONTACTS, contactRefine, toE164 } from '../../kit/contact';
import type { PrimaryContact } from '../../kit/contact';

/* ------------------------------------------------------------------ wire */
/** PENDING = staged by a maker, awaiting a checker (USER_CREATE) — cannot sign in, holds no roles. */
export type UserStatus = 'PENDING' | 'ACTIVE' | 'DISABLED' | 'ARCHIVED';

export interface RoleRef {
  id: string;
  key: string;
  name: string;
}

export interface UserRow {
  id: string;
  tenantId: string;
  username: string;
  fullName: string;
  email: string | null;
  phone: string | null;
  status: UserStatus;
  isApplicant: boolean;
  /** may resolve four-eyes approvals (docs/checker-flag.md) */
  checker: boolean;
  roles: RoleRef[];
  version: number;
  createdAt: string;
  updatedAt: string;
  archivedAt: string | null;
  /** where the IdP sends the activation; null = a legacy user born with the initial password */
  primaryContact?: PrimaryContact | null;
  /** set their own password from the activation (null when there is none to complete) */
  activated?: boolean | null;
}

/** Approved, but hasn't set a password from the IdP activation yet — can't sign in. */
export const awaitingActivation = (u: Pick<UserRow, 'status' | 'activated'>) => u.status === 'ACTIVE' && u.activated === false;

/** One page of an org's users — core's UserDtos.UserPage (AGENTS §2.7 envelope). */
export interface UserPage {
  items: UserRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  sort: string;
  /** whole-org counts per status (+ CURRENT = not removed), independent of page / search / filter */
  counts: Record<StatusCountKey, number>;
  /** the org this page lists — the signed-in org, or the org being acted as */
  org: OrgRef;
}

export interface OrgRef {
  id: string;
  legalName: string | null;
  type: string;
  subType: string | null;
  depth: number;
}

export type StatusCountKey = 'CURRENT' | 'PENDING' | 'ACTIVE' | 'DISABLED' | 'ARCHIVED';

export interface RoleOption {
  id: string;
  key: string;
  name: string;
  scope: string;
  permissionCount: number;
  assignable: boolean;
}

/* ------------------------------------------------------------ validation */
/** Mirrors UserAdminService's server rules so most mistakes are caught on blur. */
export const userFormSchema = z.object({
  username: z
    .string()
    .trim()
    .toLowerCase()
    .regex(/^[a-z0-9][a-z0-9._-]{2,63}$/, '3–64 characters: lowercase letters, digits, . _ or -'),
  fullName: z.string().trim().min(2, 'Enter the person’s full name').max(120, 'Keep it under 120 characters'),
  email: z
    .string()
    .trim()
    .max(254)
    .refine((v) => v === '' || /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(v), 'Enter a valid email address'),
  phone: z
    .string()
    .trim()
    .refine((v) => v === '' || /^\+?[0-9][0-9 ()-]{5,19}$/.test(v), 'Enter a valid phone number, e.g. +264 81 123 4567'),
  roleIds: z.array(z.string()),
  checker: z.boolean(),
  /** create only: where the IdP sends the activation (link by email / code by SMS) */
  primaryContact: z.enum(PRIMARY_CONTACTS),
});

/** Adding a user: the primary contact is required and the phone must be international (E.164)
 *  — the IdP texts the activation code to it. Mirrors core PersonProvisioning.contact. */
export const userCreateSchema = userFormSchema.superRefine((v, ctx) => contactRefine(v, ctx));

export type UserFormValues = z.infer<typeof userFormSchema>;
export type UserFormField = keyof UserFormValues;

/** Server problem code → the field it belongs to (otherwise a form-level message). */
export const FIELD_FOR_CODE: Record<string, UserFormField> = {
  USERNAME_INVALID: 'username',
  USERNAME_TAKEN: 'username',
  FULL_NAME_INVALID: 'fullName',
  EMAIL_INVALID: 'email',
  PHONE_INVALID: 'phone',
  CONTACT_REQUIRED: 'email',
  ROLE_UNKNOWN: 'roleIds',
  ROLE_NOT_ASSIGNABLE: 'roleIds',
  SELF_ROLE_CHANGE: 'roleIds',
};

/* ------------------------------------------------------------- problems */
export interface Problem {
  status: number;
  code: string;
  message: string;
}

/** ApiError carries `title` = server code for 403s and `code` otherwise (web-core/api). */
export function toProblem(err: unknown): Problem {
  if (err instanceof ApiError) {
    const code = err.status === 403 ? err.title : err.code ?? `HTTP_${err.status}`;
    return { status: err.status, code, message: err.detail };
  }
  return { status: 0, code: 'NETWORK', message: err instanceof Error ? err.message : String(err) };
}

/* ------------------------------------------------------------ query keys */
export const userKeys = {
  all: ['users'] as const,
  org: (orgId: string) => ['users', 'org', orgId] as const,
  lists: (orgId: string) => ['users', 'org', orgId, 'list'] as const,
  list: (orgId: string, q: UserListQuery) => ['users', 'org', orgId, 'list', q] as const,
  user: (orgId: string, id: string) => ['users', 'org', orgId, 'user', id] as const,
  roles: (orgId: string) => ['users', 'org', orgId, 'roles'] as const,
};

/* ------------------------------------------------------------------ reads */
/** What the list asks core for. Search, filter, sort and paging all run server-side (AGENTS §2.7). */
export interface UserListQuery {
  page: number;
  size: number;
  q: string;
  status: StatusFilter;
  sort: SortId;
}

export const PAGE_SIZES = [20, 50, 100] as const;
export const DEFAULT_PAGE_SIZE = 20;

/** `users.list` params; the source's allow-list decides what reaches the URL, blanks are dropped. */
export function listParams(orgId: string, q: UserListQuery): Record<string, string> {
  const out: Record<string, string> = {
    tenantId: orgId, page: String(q.page), size: String(q.size), status: q.status, sort: q.sort,
  };
  const text = q.q.trim();
  if (text) out.q = text;
  return out;
}

export function useUserList(port: WidgetDataPortLike, orgId: string, q: UserListQuery) {
  return useQuery({
    queryKey: userKeys.list(orgId, q),
    queryFn: () => port.request<UserPage>('users.list', listParams(orgId, q)),
    // no org yet (a "selected org" screen before the pick) → no request to /tenants//users
    enabled: Boolean(orgId),
    // keep the previous page while paging the SAME org; never show one org's users under another
    placeholderData: (prev, prevQuery) => (prevQuery?.queryKey[2] === orgId ? prev : undefined),
  });
}

/**
 * The open user, by id — they may not be on the page being shown (an Approvals deep link,
 * a search that no longer matches). Seeded from the page row when it is there.
 */
export function useUser(port: WidgetDataPortLike, orgId: string, id: string | null, row: UserRow | undefined) {
  return useQuery({
    queryKey: userKeys.user(orgId, id ?? ''),
    queryFn: () => port.request<UserRow>('users.get', { tenantId: orgId, personId: id ?? '' }),
    enabled: Boolean(id) && id !== 'new',
    initialData: row,
    retry: false,
  });
}

export function useRoleOptions(port: WidgetDataPortLike, orgId: string, enabled: boolean) {
  return useQuery({
    queryKey: userKeys.roles(orgId),
    queryFn: () => port.request<RoleOption[]>('users.roles', { tenantId: orgId }),
    enabled,
    staleTime: 5 * 60_000,
  });
}

/* ----------------------------------------------------------------- writes */
export type UserCommand =
  | { kind: 'create'; values: UserFormValues }
  | { kind: 'update'; user: UserRow; values: UserFormValues; rolesEditable: boolean }
  | { kind: 'deactivate'; user: UserRow }
  | { kind: 'activate'; user: UserRow }
<<<<<<< Updated upstream
  | { kind: 'remove'; user: UserRow }
  | { kind: 'resend'; user: UserRow };
=======
  | { kind: 'password-reset'; user: UserRow }
  | { kind: 'remove'; user: UserRow };
>>>>>>> Stashed changes

const blankToEmpty = (v: string) => v.trim();

/** POST body of user.create — contact + channel for the IdP activation; never a password. */
export function createUserBody(v: UserFormValues) {
  return {
    username: v.username,
    fullName: v.fullName,
    email: blankToEmpty(v.email).toLowerCase(),
    phone: toE164(v.phone.trim()) ?? blankToEmpty(v.phone),
    roleIds: v.roleIds,
    checker: v.checker,
    primaryContact: v.primaryContact,
  };
}

/**
 * Every user command, never optimistic, never auto-retried; on settle the org's
 * user list is invalidated explicitly (ui-std §7).
 */
export function useUserCommand(port: WidgetDataPortLike, orgId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (cmd: UserCommand): Promise<UserRow> => {
      const base = { tenantId: orgId };
      switch (cmd.kind) {
        case 'create':
          return port.command<UserRow>('user.create', createUserBody(cmd.values), base);
        case 'update':
          return port.command<UserRow>(
            'user.update',
            {
              fullName: cmd.values.fullName,
              email: blankToEmpty(cmd.values.email),
              phone: blankToEmpty(cmd.values.phone),
              roleIds: cmd.rolesEditable ? cmd.values.roleIds : null,
              checker: cmd.rolesEditable ? cmd.values.checker : null,
              expectedVersion: cmd.user.version,
            },
            { ...base, personId: cmd.user.id },
          );
        case 'deactivate':
          return port.command<UserRow>('user.deactivate', { expectedVersion: cmd.user.version }, { ...base, personId: cmd.user.id });
        case 'activate':
          return port.command<UserRow>('user.activate', { expectedVersion: cmd.user.version }, { ...base, personId: cmd.user.id });
<<<<<<< Updated upstream
        case 'resend':
          // acknowledgement only ({personId, channel}) — the list re-sync shows nothing new
          return port.command<UserRow>('user.activation.resend', {}, { ...base, personId: cmd.user.id });
=======
        case 'password-reset':
          return port.command<UserRow>(
            'user.password-reset',
            { expectedVersion: cmd.user.version },
            { ...base, personId: cmd.user.id },
          );
>>>>>>> Stashed changes
        case 'remove':
          return port.command<UserRow>('user.remove', undefined, {
            ...base,
            personId: cmd.user.id,
            expectedVersion: String(cmd.user.version),
          });
      }
    },
    onSuccess: (saved) => {
      // write-through the confirmed server row for the open drawer, then re-sync every page
      // (a fixture/acknowledgement response without a row only triggers the re-sync)
      if (!saved || typeof saved.id !== 'string') return;
      qc.setQueryData<UserRow>(userKeys.user(orgId, saved.id), saved);
    },
    onSettled: () => qc.invalidateQueries({ queryKey: userKeys.org(orgId) }),
  });
}

/* ---------------------------------------------------------------- helpers */
export function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  const a = parts[0]?.[0] ?? '?';
  const b = parts.length > 1 ? parts[parts.length - 1][0] : '';
  return (a + b).toUpperCase();
}

const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });

export function relativeTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const diff = (new Date(iso).getTime() - Date.now()) / 1000;
  const abs = Math.abs(diff);
  if (abs < 60) return rtf.format(Math.round(diff), 'second');
  if (abs < 3600) return rtf.format(Math.round(diff / 60), 'minute');
  if (abs < 86400) return rtf.format(Math.round(diff / 3600), 'hour');
  if (abs < 86400 * 30) return rtf.format(Math.round(diff / 86400), 'day');
  return new Date(iso).toLocaleDateString();
}

export function absoluteTime(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString() : '—';
}

export type StatusFilter = 'current' | 'PENDING' | 'ACTIVE' | 'DISABLED' | 'ARCHIVED';

export const STATUS_FILTERS: { id: StatusFilter; label: string; countKey: StatusCountKey }[] = [
  { id: 'current', label: 'All', countKey: 'CURRENT' },
  { id: 'PENDING', label: 'Awaiting approval', countKey: 'PENDING' },
  { id: 'ACTIVE', label: 'Active', countKey: 'ACTIVE' },
  { id: 'DISABLED', label: 'Deactivated', countKey: 'DISABLED' },
  { id: 'ARCHIVED', label: 'Removed', countKey: 'ARCHIVED' },
];

/** Sort choices — each is on core's safelist (UserAdminService.SORT_FIELDS). */
export const SORT_OPTIONS = [
  { id: 'username,asc', label: 'Username A–Z' },
  { id: 'fullName,asc', label: 'Name A–Z' },
  { id: 'fullName,desc', label: 'Name Z–A' },
  { id: 'updatedAt,desc', label: 'Recently updated' },
  { id: 'createdAt,desc', label: 'Newest first' },
  { id: 'createdAt,asc', label: 'Oldest first' },
] as const;

export type SortId = (typeof SORT_OPTIONS)[number]['id'];
export const DEFAULT_SORT: SortId = 'username,asc';
