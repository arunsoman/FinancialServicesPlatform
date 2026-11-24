/**
 * The portal's published menus could not be loaded or read — a network/server error, or a payload
 * this build doesn't understand. Retrying is safe (a read).
 */
import type { ReactNode } from 'react';

export interface MenusUnavailableProps {
  /** the portal's display name (PortalConfig.label) */
  label: string;
  /** what went wrong, in words */
  detail: string;
  onRetry: () => void;
}

export function MenusUnavailable({ label, detail, onRetry }: MenusUnavailableProps): ReactNode {
  return (
    <div role="alert" className="grid h-[100dvh] place-items-center px-4">
      <div className="fsp-panel max-w-lg p-6" data-state="menus-unavailable">
        <div className="fsp-eyebrow mb-2">{label}</div>
        <h1 className="mb-3 text-xl font-bold">Couldn’t load the {label} menus</h1>
        <p className="text-dim mb-4 text-sm leading-relaxed">{detail}</p>
        <button type="button" className="fsp-btn fsp-btn-primary px-4 py-2 text-sm" onClick={onRetry}>Try again</button>
      </div>
    </div>
  );
}
