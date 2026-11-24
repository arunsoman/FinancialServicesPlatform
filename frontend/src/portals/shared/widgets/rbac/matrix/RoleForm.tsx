import { useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { Alert, Button, Chip } from '../../../ui/atoms';
import { FormField } from '../../kit/FormField';
import { InlineConfirm } from '../../kit/InlineConfirm';
import { SelectField } from '../../kit/SelectField';
import { StatePanel } from '../../kit/StatePanel';
import type { MenuAccess } from '../../../../signed/signed-layout';
import { PermissionMatrix } from './PermissionMatrix';
import { SectionHeading } from './SectionHeading';
import { NOTHING_GRANTABLE } from './model';
import type { Grantable, Grants, MenuState, OrgUnitPage, PermissionDef, RoleDetailsBody, RoleView } from './model';
import { useRoleDraft } from './use-role-draft';

/** A select's options as loaded (null while loading / on error — the select then says so). */
export interface UnitOptions {
  page: OrgUnitPage | null;
  error: string | null;
}

export interface RoleFormProps {
  /** absent = create a new role */
  role: RoleView | null;
  /** the published menu of the portal the role's holders sign in to — the matrix rows */
  menu: MenuState;
  /** what the editor holds on that menu (null while loading) — only that can be granted */
  editorAccess: MenuAccess | null;
  catalog: PermissionDef[];
  branches: UnitOptions;
  departments: UnitOptions;
  /** permission keys the editor holds */
  held: ReadonlySet<string>;
  canManage: boolean;
  busy: boolean;
  problem: string | null;
  onCancel: () => void;
  onCreate: (key: string, details: RoleDetailsBody, grants: Grants) => void;
  onRequestChange: (details: RoleDetailsBody, grants: Grants) => void;
  onRequestDelete: () => void;
}

const options = (u: UnitOptions) => (u.page?.items ?? []).map((o) => ({ value: o.id, label: o.name }));
const unitHint = (u: UnitOptions, what: string) =>
  u.error ? `Couldn’t load ${what}: ${u.error}`
    : !u.page ? `Loading ${what}…`
    : u.page.items.length === 0 ? `This organization has no ${what} yet (add them under Role Management)`
    : u.page.hasNext ? `Showing the first ${u.page.items.length} of ${u.page.totalElements}`
    : undefined;

/**
 * Maker surface for one role (create or edit): Role Details + Permissions Matrix. Nothing applies
 * until a checker approves in Approvals — there is no approve control here (ui-std §5.2).
 */
export function RoleForm(p: RoleFormProps) {
  const { role, menu, editorAccess, catalog, branches, departments, held, canManage, busy, problem } = p;
  const creating = role === null;
  const unitsRequired = useMemo(() => ({
    branch: (branches.page?.items.length ?? 0) > 0,
    department: (departments.page?.items.length ?? 0) > 0,
  }), [branches.page, departments.page]);
  const draft = useRoleDraft(role, unitsRequired);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const pending = role !== null && (role.status === 'PENDING_CREATE' || role.pendingApprovalId !== null);
  const builtIn = role?.fullMenu === true;
  // no verified menu / unknown editor access → nothing to grant against: the role can be read, not changed
  const editable = canManage && !pending && role?.scope !== 'PLATFORM' && menu.kind === 'ready' && editorAccess !== null;
  const grantable: Grantable = editable && !builtIn && editorAccess ? { keys: held, menu: editorAccess } : NOTHING_GRANTABLE;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!editable) return;
    const details = draft.validate();
    if (!details) return;
    if (creating) p.onCreate(draft.key, details, draft.grants);
    else if (draft.changed) p.onRequestChange(details, draft.grants);
  };

  return (
    <form onSubmit={submit} noValidate className="mx-auto grid max-w-5xl gap-8" data-role-form>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <Button type="button" variant="ghost" onClick={p.onCancel}>← Roles</Button>
          <h1 className="mt-2 text-[22px] font-bold">{creating ? 'Add Role' : `Edit ${role.name}`}</h1>
        </div>
        {builtIn && <Chip tone="info">Built-in — sees the whole menu</Chip>}
      </div>

      {pending && (
        <Alert kind="warn">
          {role.status === 'PENDING_CREATE' ? 'This role is awaiting approval to be created.' : `A ${role.pendingOperation === 'ROLE_DELETE' ? 'deletion' : 'change'} is awaiting approval.`}
          {' '}Reference <span className="mono">{role.pendingApprovalId?.slice(0, 8)}</span> ·{' '}
          <Link className="underline" to="/approvals">open approvals</Link>
        </Alert>
      )}
      {!canManage && <Alert kind="info">You can view roles. Changing them needs the role.manage permission.</Alert>}

      <section aria-labelledby="role-details">
        <SectionHeading id="role-details" title="Role Details" subtitle={creating ? 'Basic identification for the new role.' : 'Identification of this role.'} />
        <div className="grid gap-4 rounded-2xl border border-line bg-surface p-5 sm:grid-cols-2">
          <FormField label="Role Name *" placeholder="e.g. Teller Supervisor" value={draft.values.name} error={draft.errors.name}
            hint={draft.key ? `Saved as ${draft.key}` : undefined}
            disabled={!editable} autoComplete="off"
            onChange={(e) => draft.set('name', e.target.value)} onBlur={() => draft.touch('name')} />
          <FormField label="Role Description *" placeholder="Brief description of responsibilities" value={draft.values.description}
            error={draft.errors.description} disabled={!editable} autoComplete="off"
            onChange={(e) => draft.set('description', e.target.value)} onBlur={() => draft.touch('description')} />
          <SelectField label={unitsRequired.branch ? 'Branch *' : 'Branch'} options={options(branches)} placeholder="Select a branch"
            value={draft.values.branchId} error={draft.errors.branchId} hint={unitHint(branches, 'branches')}
            disabled={!editable || !branches.page?.items.length}
            onChange={(e) => draft.set('branchId', e.target.value)} onBlur={() => draft.touch('branchId')} />
          <SelectField label={unitsRequired.department ? 'Department *' : 'Department'} options={options(departments)} placeholder="Select a department"
            value={draft.values.departmentId} error={draft.errors.departmentId} hint={unitHint(departments, 'departments')}
            disabled={!editable || !departments.page?.items.length}
            onChange={(e) => draft.set('departmentId', e.target.value)} onBlur={() => draft.touch('departmentId')} />
        </div>
      </section>

      <section aria-labelledby="role-matrix">
        <SectionHeading id="role-matrix" title="Permissions Matrix" subtitle="Grant granular access to system components and subcomponents." />
        {menu.kind === 'ready' && (
          <p className="text-dim mb-2 text-[12px]" data-menu-source>
            Rows: the {menu.label} portal’s published menu, version <span className="mono">{menu.layout.layoutVersion}</span>.
            Active shows the submenu in that portal’s sidebar.
          </p>
        )}
        {menu.kind === 'ready' ? (
          <PermissionMatrix layout={menu.layout} catalog={catalog} value={draft.grants} grantable={grantable} fullMenu={builtIn}
            onChange={draft.setGrants} />
        ) : menu.kind === 'loading' ? (
          <StatePanel kind="loading" title="Loading the published menu" />
        ) : menu.kind === 'missing' ? (
          <StatePanel kind="empty"
            title={menu.portal ? `The ${menu.portal} portal has no published menu yet` : 'This organization signs in to no portal'}
            hint={menu.portal ? 'Permissions are granted per submenu. Publish this portal’s layout in the portal builder, then come back.' : undefined} />
        ) : menu.kind === 'invalid' ? (
          <StatePanel kind="error" title={`The ${menu.portal} portal’s published menu couldn’t be read`} hint={menu.reason} />
        ) : (
          <StatePanel kind="error" title="Couldn’t load the published menu" hint={menu.message} onRetry={menu.retry} />
        )}
      </section>

      {problem && <Alert kind="bad">{problem}</Alert>}

      {editable && (
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="text-dim text-[12px]" data-role-summary>
            {creating
              ? `${draft.grants.cells.size} menu permission(s) selected. A checker approves the new role before it can be assigned.`
              : !draft.changed ? 'No changes yet.'
              : [
                draft.detailsChanged ? 'details changed' : '',
                draft.cellsAdded.length ? `${draft.cellsAdded.length} menu permission(s) to add` : '',
                draft.cellsRemoved.length ? `${draft.cellsRemoved.length} to remove` : '',
                draft.keysAdded.length ? `keys +${draft.keysAdded.join(', ')}` : '',
                draft.keysRemoved.length ? `keys −${draft.keysRemoved.join(', ')}` : '',
              ].filter(Boolean).join(' · ')}
          </div>
          <div className="flex gap-2">
            {!creating && !role.protectedRole && (
              <Button type="button" variant="ghost" disabled={busy || role.assignedCount > 0} data-action="role.delete"
                title={role.assignedCount > 0 ? 'Unassign this role from everyone first' : undefined}
                onClick={() => setConfirmDelete(true)}>
                Delete role
              </Button>
            )}
            <Button type="button" variant="ghost" onClick={p.onCancel} disabled={busy}>Cancel</Button>
            <Button type="submit" variant="primary" data-action={creating ? 'role.create' : 'role.update'}
              disabled={busy || (!creating && !draft.changed)}>
              {busy ? 'Sending…' : creating ? 'Submit for approval' : 'Request change'}
            </Button>
          </div>
        </div>
      )}
      {confirmDelete && role && (
        <InlineConfirm title={`Delete ${role.name}?`} confirmLabel="Request deletion" tone="danger" busy={busy}
          onCancel={() => setConfirmDelete(false)} onConfirm={p.onRequestDelete}>
          A checker must approve the deletion before the role disappears.
        </InlineConfirm>
      )}
    </form>
  );
}
