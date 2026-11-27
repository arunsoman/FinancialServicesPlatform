/** Minor-unit formatting (ui-std §5.3) — string math only, never floats (NUMERIC(20,0) safe). */
const EXPONENT: Record<string, number> = { JPY: 0, KWD: 3, BHD: 3 };

export function formatMinor(value: string | number, currency: string): string {
  const exp = EXPONENT[currency] ?? 2;
  const raw = String(value).trim();
  const neg = raw.startsWith('-');
  const digits = (neg ? raw.slice(1) : raw).replace(/^0+(?=\d)/, '') || '0';
  const padded = digits.padStart(exp + 1, '0');
  const whole = exp === 0 ? padded : padded.slice(0, padded.length - exp);
  const frac = exp === 0 ? '' : padded.slice(padded.length - exp);
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${neg ? '-' : ''}${grouped}${frac ? `.${frac}` : ''}`;
}
