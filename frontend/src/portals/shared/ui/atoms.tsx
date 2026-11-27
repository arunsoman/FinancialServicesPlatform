/**
 * Design-system atoms — SME atoms (mp-button/mp-card/status-badge/kv-pair/
 * page-header/empty-state) ported to React on the shared token classes.
 * Every visual decision rides tokens.css + Tailwind semantic colors; feature
 * screens never hardcode a color.
 */
import type { ButtonHTMLAttributes, HTMLAttributes, InputHTMLAttributes, ReactNode } from 'react';
import { forwardRef, useCallback, useEffect, useRef, useState } from 'react';
import type { MouseEvent } from 'react';
import { clsx } from 'clsx';
import { statusStyle } from '../ui/status-maps';
import type { StatusTone } from '../ui/status-maps';

/* ------------------------------------------------ button (pill, variants) */
type ButtonVariant = 'primary' | 'ghost' | 'danger' | 'plain';
export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
}
/**
 * An `onClick` that returns a Promise disables the button until it settles (one press = one
 * action). The global submit guard (web-core/submit-guard.ts) additionally drops repeat clicks
 * and holds the button while any mutation it started is in flight.
 */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'plain', className, onClick, disabled, ...rest }, ref,
) {
  const [pending, setPending] = useState(false);
  const pendingRef = useRef(false);
  const mounted = useRef(true);
  useEffect(() => () => { mounted.current = false; }, []);
  const handleClick = useCallback((e: MouseEvent<HTMLButtonElement>) => {
    if (pendingRef.current) { e.preventDefault(); return; }
    const out: unknown = onClick?.(e);
    if (!(out instanceof Promise)) return;
    pendingRef.current = true;
    setPending(true);
    const done = () => {
      pendingRef.current = false;
      if (mounted.current) setPending(false);
    };
    out.then(done, done);
  }, [onClick]);
  return (
    <button
      ref={ref}
      className={clsx(
        'fsp-btn',
        variant === 'primary' && 'fsp-btn-primary',
        variant === 'danger' && 'fsp-btn-danger',
        variant === 'ghost' && 'fsp-btn-ghost',
        className,
      )}
      disabled={disabled || pending}
      aria-busy={pending || undefined}
      onClick={handleClick}
      {...rest}
    />
  );
});

/* ------------------------------------------------------------ panel + card */
export function Panel({ className, children, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return (
    <section className={clsx('fsp-panel', className)} {...rest}>
      {children}
    </section>
  );
}

/* ----------------------------------------------------------- chip + badge */
const CHIP_TONE: Record<StatusTone, string> = {
  ok: 'fsp-chip-ok', warn: 'fsp-chip-warn', bad: 'fsp-chip-bad',
  info: 'fsp-chip-info', neutral: '', accent: 'fsp-chip-accent',
};

export function Chip({ children, tone = 'neutral' }: { children: ReactNode; tone?: StatusTone }) {
  return <span className={clsx('fsp-chip', CHIP_TONE[tone])}>{children}</span>;
}

export function StatusBadge({ status }: { status: string | null | undefined }) {
  const s = statusStyle(status);
  return <Chip tone={s.tone}>{s.label}</Chip>;
}

/* ---------------------------------------------------------------- fields */
export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className, ...props }, ref) {
    // className merged AFTER the spread so callers extend, never replace, the field style
    return <input ref={ref} {...props} className={clsx('fsp-field', className)} />;
  },
);

/* ------------------------------------------------------------ page header */
export function PageHeader({
  title, subtitle, actions,
}: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <header className="mb-4 flex flex-wrap items-start justify-between gap-3">
      <div>
        <h2 className="text-xl">{title}</h2>
        {subtitle && <p className="text-dim mt-1 text-[13px]">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </header>
  );
}

/* -------------------------------------------------------------- kv pairs */
export function KvPair({ label, children, mono }: { label: string; children: ReactNode; mono?: boolean }) {
  return (
    <div className="min-w-0">
      <div className="text-dim mb-0.5 text-[10.5px] font-bold uppercase tracking-[.14em]">{label}</div>
      <div className={clsx('truncate text-sm', mono && 'mono')}>{children ?? '—'}</div>
    </div>
  );
}

/* ------------------------------------------------------------ empty state */
export function EmptyState({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="text-dim grid place-items-center rounded-xl border border-dashed border-line px-4 py-10 text-center">
      <div className="text-sm font-semibold text-dim">{title}</div>
      {hint && <div className="mt-1 text-xs">{hint}</div>}
    </div>
  );
}

/* ----------------------------------------------------------------- alert */
export function Alert({ kind, children }: { kind: 'ok' | 'warn' | 'bad' | 'info'; children: ReactNode }) {
  return (
    <div
      role={kind === 'bad' ? 'alert' : 'status'}
      className={clsx('fsp-chip block whitespace-normal px-3 py-2 text-left text-xs normal-case tracking-normal', CHIP_TONE[kind])}
    >
      {children}
    </div>
  );
}
