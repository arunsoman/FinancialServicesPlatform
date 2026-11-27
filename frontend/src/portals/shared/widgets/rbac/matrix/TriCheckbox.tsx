import { useEffect, useRef } from 'react';
import type { CheckState } from './model';

export interface TriCheckboxProps {
  state: CheckState;
  disabled: boolean;
  label: string;
  title?: string;
  onToggle: () => void;
}

/** A "select all" box: checked / unchecked / indeterminate (some of the row or group selected). */
export function TriCheckbox({ state, disabled, label, title, onToggle }: TriCheckboxProps) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = state === 'some';
  }, [state]);
  return (
    <input
      ref={ref}
      type="checkbox"
      className="fsp-matrix-box"
      aria-label={label}
      title={title}
      checked={state === 'all'}
      aria-checked={state === 'some' ? 'mixed' : state === 'all'}
      disabled={disabled}
      onChange={onToggle}
    />
  );
}
