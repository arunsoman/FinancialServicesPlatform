/**
 * THE money atom (ui-std §5.3): minor units in (string or number — strings are JS-safe for
 * NUMERIC(20,0)), formatted with the currency's exponent, tabular and right-aligned. A debit
 * is colored as a debit, never as an error. The client formats; it never sums to verify.
 */
import { clsx } from 'clsx';
import { formatMinor } from './money';

export interface AmountProps {
  /** minor units (e.g. "250000" = 2,500.00 NAD) */
  value: string | number;
  currency: string;
  direction?: 'debit' | 'credit';
  className?: string;
}

export function Amount({ value, currency, direction, className }: AmountProps) {
  return (
    <span
      className={clsx('tabular-nums whitespace-nowrap text-right', className)}
      style={direction ? { color: `var(--color-financial-${direction})` } : undefined}
    >
      {formatMinor(value, currency)} <span className="text-dim text-[11px]">{currency}</span>
    </span>
  );
}
