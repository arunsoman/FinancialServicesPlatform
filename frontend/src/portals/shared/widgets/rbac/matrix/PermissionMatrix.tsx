import { Fragment, useMemo } from 'react';
import { clsx } from 'clsx';
import type { SignedLayout } from '../../../../signed/signed-layout';
import { getWidget } from '../../registry';
import { TriCheckbox } from './TriCheckbox';
import {
  CELLS_PER_ROW, COLUMN_LABEL, MATRIX_COLUMNS, buildMatrix, canGrantCell, checkState, columnCell, rowCells, toggleCell, toggleRows,
} from './model';
import type { Grantable, Grants, MatrixColumn, MatrixRow, PermissionDef, PermissionMatrixModel } from './model';

export interface PermissionMatrixProps {
  /** the verified, PUBLISHED layout of the portal the role's holders sign in to — the rows ARE its menu */
  layout: SignedLayout;
  catalog: PermissionDef[];
  value: Grants;
  /** what the editor may grant (their own keys + menu cells); nothing when read-only */
  grantable: Grantable;
  /** the role holds the whole menu implicitly (built-in roles): every box shows ticked, locked */
  fullMenu: boolean;
  onChange: (next: Grants) => void;
}

const HEAD = 'whitespace-nowrap px-3 py-3 text-[10.5px] font-bold uppercase tracking-[.08em]';
const LOCKED = 'You can only grant or revoke access you hold yourself';

/**
 * Menu component × action grid (the Add Role screen): one header row per published menu with its
 * own Select all, one indented row per submenu with Select all · Create · View · Update · Delete ·
 * Authorize · Active. Each box is one menu cell on the role (plus the keys that screen needs for
 * that action — listed in the box's tooltip). Boxes the editor may not grant are locked.
 */
export function PermissionMatrix({ layout, catalog, value, grantable, fullMenu, onChange }: PermissionMatrixProps) {
  const matrix: PermissionMatrixModel = useMemo(() => buildMatrix(layout, catalog, getWidget), [layout, catalog]);
  const cells = fullMenu ? new Set(matrix.groups.flatMap((g) => g.rows.flatMap(rowCells))) : value.cells;
  const g: Grantable = fullMenu ? { keys: new Set(), menu: { full: false, cells: new Set() } } : grantable;
  const anyGrantable = (cs: readonly string[]) => cs.some((c) => canGrantCell(g, c));

  const box = (r: MatrixRow, column: MatrixColumn | 'active') => {
    const cell = columnCell(r, column);
    const locked = !canGrantCell(g, cell);
    const label = column === 'active' ? 'Active' : COLUMN_LABEL[column];
    const keys = column === 'active' ? [] : r.keys[column].map((k) => `${k.key} — ${k.title}`);
    const title = [
      column === 'active' ? `Shows “${r.label}” in the menu` : `${label} on “${r.label}”`,
      keys.length ? `Also grants:\n${keys.join('\n')}` : '',
      fullMenu ? 'Built-in role: holds the whole menu' : locked ? `(${LOCKED})` : '',
    ].filter(Boolean).join('\n');
    return (
      <TriCheckbox state={cells.has(cell) ? 'all' : 'none'} disabled={locked}
        label={`${label} ${r.label}`} title={title}
        onToggle={() => onChange(toggleCell(r, column, value, g))} />
    );
  };

  return (
    <div className="overflow-x-auto rounded-2xl border border-line">
      <table className="w-full border-separate border-spacing-0 text-sm" data-permission-matrix>
        <thead className="bg-[var(--color-action-secondary)] text-[var(--color-action-text)]">
          <tr>
            <th scope="col" className={clsx(HEAD, 'text-left')}>Menu component</th>
            <th scope="col" className={clsx(HEAD, 'text-center')}>#</th>
            <th scope="col" className={clsx(HEAD, 'text-center')}>Select all</th>
            {MATRIX_COLUMNS.map((c) => <th key={c} scope="col" className={clsx(HEAD, 'text-center')}>{COLUMN_LABEL[c]}</th>)}
            <th scope="col" className={clsx(HEAD, 'text-center')}>Active</th>
          </tr>
        </thead>
        <tbody>
          {matrix.groups.map((grp) => {
            const groupCells = grp.rows.flatMap(rowCells);
            return (
              <Fragment key={grp.id}>
                <tr className="bg-surface" data-menu-group={grp.id}>
                  <th scope="row" className="border-t border-line px-4 py-3 text-left text-[13px] font-bold">{grp.label}</th>
                  <td className="text-dim border-t border-line px-3 py-3 text-center text-[12px] tabular-nums">
                    {groupCells.filter((c) => cells.has(c)).length}/{groupCells.length}
                  </td>
                  <td className="border-t border-line px-3 py-3 text-center">
                    <TriCheckbox state={checkState(groupCells, cells)} disabled={!anyGrantable(groupCells)}
                      label={`Select all ${grp.label}`} title={anyGrantable(groupCells) ? undefined : LOCKED}
                      onToggle={() => onChange(toggleRows(grp.rows, value, g))} />
                  </td>
                  <td colSpan={MATRIX_COLUMNS.length + 1} className="border-t border-line" />
                </tr>
                {grp.rows.map((r) => {
                  const rc = rowCells(r);
                  return (
                    <tr key={r.link} className="bg-surface-sunken" data-menu-link={r.link}>
                      <th scope="row" className="border-t border-line py-2.5 pl-10 pr-3 text-left text-[13px] font-medium">{r.label}</th>
                      <td className="text-dim border-t border-line px-3 py-2.5 text-center text-[12px] tabular-nums">
                        {rc.filter((c) => cells.has(c)).length}/{CELLS_PER_ROW}
                      </td>
                      <td className="border-t border-line px-3 py-2.5 text-center">
                        <TriCheckbox state={checkState(rc, cells)} disabled={!anyGrantable(rc)}
                          label={`Select all ${r.label}`} title={anyGrantable(rc) ? undefined : LOCKED}
                          onToggle={() => onChange(toggleRows([r], value, g))} />
                      </td>
                      {MATRIX_COLUMNS.map((c) => (
                        <td key={c} className="border-t border-line px-3 py-2.5 text-center">{box(r, c)}</td>
                      ))}
                      <td className="border-t border-line px-3 py-2.5 text-center">{box(r, 'active')}</td>
                    </tr>
                  );
                })}
              </Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
