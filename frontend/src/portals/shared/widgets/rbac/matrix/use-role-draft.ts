/** The Add / Edit Role form's state: Role Details values + the grants being built, and what changed. */
import { useEffect, useMemo, useState } from 'react';
import { cellDiff, roleDetailsSchema, roleKeyOf } from './model';
import type { Grants, RoleDetailsBody, RoleDetailsValues, RoleView } from './model';

type Errors = Partial<Record<keyof RoleDetailsValues, string>>;

function initial(role: RoleView | null): RoleDetailsValues {
  return {
    name: role?.name ?? '',
    description: role?.description ?? '',
    branchId: role?.branchId ?? '',
    departmentId: role?.departmentId ?? '',
  };
}

function grantsOf(role: RoleView | null): Grants {
  return { keys: new Set(role?.permissionKeys ?? []), cells: new Set(role?.menuCells ?? []) };
}

export interface RoleDraft {
  values: RoleDetailsValues;
  errors: Errors;
  set: (field: keyof RoleDetailsValues, value: string) => void;
  /** validate one field on blur (ui-std §8) */
  touch: (field: keyof RoleDetailsValues) => void;
  /** validate everything; the request body when valid */
  validate: () => RoleDetailsBody | null;
  /** the role key the name saves as */
  key: string;
  grants: Grants;
  setGrants: (g: Grants) => void;
  keysAdded: string[];
  keysRemoved: string[];
  cellsAdded: string[];
  cellsRemoved: string[];
  detailsChanged: boolean;
  changed: boolean;
}

export function useRoleDraft(role: RoleView | null, unitsRequired: { branch: boolean; department: boolean }): RoleDraft {
  const [values, setValues] = useState<RoleDetailsValues>(() => initial(role));
  const [errors, setErrors] = useState<Errors>({});
  const [grants, setGrants] = useState<Grants>(() => grantsOf(role));
  // a refetched role (new version after approval) resets the draft
  useEffect(() => {
    setValues(initial(role));
    setGrants(grantsOf(role));
    setErrors({});
  }, [role]);

  const schema = useMemo(() => roleDetailsSchema(unitsRequired), [unitsRequired]);
  const run = (only?: keyof RoleDetailsValues): RoleDetailsValues | null => {
    const r = schema.safeParse(values);
    const next: Errors = {};
    if (!r.success) for (const issue of r.error.issues) next[issue.path[0] as keyof RoleDetailsValues] ??= issue.message;
    setErrors((e) => (only ? { ...e, [only]: next[only] } : next));
    return r.success ? r.data : null;
  };

  const baseKeys = role?.permissionKeys ?? [];
  const keyDiff = cellDiff(baseKeys, grants.keys);
  const cells = cellDiff(role?.menuCells ?? [], grants.cells);
  const start = initial(role);
  const detailsChanged = (Object.keys(start) as Array<keyof RoleDetailsValues>).some((k) => start[k].trim() !== values[k].trim());

  return {
    values,
    errors,
    set: (field, value) => setValues((v) => ({ ...v, [field]: value })),
    touch: (field) => void run(field),
    validate: () => {
      const ok = run();
      return ok
        ? { name: ok.name.trim(), description: ok.description.trim(), branchId: ok.branchId || null, departmentId: ok.departmentId || null }
        : null;
    },
    key: role?.key ?? roleKeyOf(values.name),
    grants,
    setGrants,
    keysAdded: keyDiff.added,
    keysRemoved: keyDiff.removed,
    cellsAdded: cells.added,
    cellsRemoved: cells.removed,
    detailsChanged,
    changed: detailsChanged || keyDiff.added.length + keyDiff.removed.length + cells.added.length + cells.removed.length > 0,
  };
}
