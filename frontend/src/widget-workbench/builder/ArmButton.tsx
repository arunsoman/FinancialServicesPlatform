import { useEffect, useState } from 'react';

export interface ArmButtonProps {
  label: string;
  armedLabel: string;
  onConfirm: () => void;
  className?: string;
  disabled?: boolean;
  title?: string;
}

/** Two-click confirm for destructive edits (no browser dialogs): arms for 4 s, then resets. */
export function ArmButton({ label, armedLabel, onConfirm, className = 'lnk bad', disabled, title }: ArmButtonProps) {
  const [armed, setArmed] = useState(false);
  useEffect(() => {
    if (!armed) return undefined;
    const t = setTimeout(() => setArmed(false), 4000);
    return () => clearTimeout(t);
  }, [armed]);
  return (
    <button
      type="button"
      className={className}
      disabled={disabled}
      title={title}
      onClick={() => {
        if (armed) {
          setArmed(false);
          onConfirm();
        } else setArmed(true);
      }}
    >
      {armed ? armedLabel : label}
    </button>
  );
}
