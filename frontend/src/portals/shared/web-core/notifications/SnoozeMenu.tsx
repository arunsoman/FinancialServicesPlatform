import { useMemo, useState } from 'react';
import { Button } from '../../ui/atoms';
import { snoozeOptions } from './model';

export interface SnoozeMenuProps {
  busy: boolean;
  onSnooze: (until: Date) => void;
}

/** "Snooze ▾" → a short list of times; the chosen one is sent, nothing is guessed. */
export function SnoozeMenu({ busy, onSnooze }: SnoozeMenuProps) {
  const [open, setOpen] = useState(false);
  // recomputed each time the menu opens, so "in 1 hour" is from now, not from page load
  const options = useMemo(() => (open ? snoozeOptions(new Date()) : []), [open]);

  if (!open) {
    return (
      <Button variant="ghost" className="px-3 py-1 text-xs" disabled={busy} aria-haspopup="menu" onClick={() => setOpen(true)}>
        Snooze
      </Button>
    );
  }
  return (
    <div role="menu" aria-label="Snooze until" className="flex flex-wrap items-center gap-1.5">
      {options.map((o) => (
        <button
          key={o.id}
          type="button"
          role="menuitem"
          disabled={busy}
          className="fsp-chip hover:border-line-strong"
          onClick={() => {
            setOpen(false);
            onSnooze(o.until);
          }}
        >
          {o.label}
        </button>
      ))}
      <button type="button" className="text-dim px-1 text-xs underline" onClick={() => setOpen(false)}>
        Cancel
      </button>
    </div>
  );
}
