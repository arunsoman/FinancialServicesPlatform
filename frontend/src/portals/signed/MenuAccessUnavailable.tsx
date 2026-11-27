/**
 * The portal's menu loaded, but the person's menu access could not be read — so nothing on the
 * menu can be shown (fail closed). Retrying is safe (a read).
 */
import type { ReactNode } from 'react';

export interface MenuAccessUnavailableProps {
  /** the portal's display name (PortalConfig.label) */
  label: string;
  onRetry: () => void;
}

export function MenuAccessUnavailable({ label, onRetry }: MenuAccessUnavailableProps): ReactNode {
  return (
    <div role="alert" className="grid h-[100dvh] place-items-center px-4">
      <div className="fsp-panel max-w-lg p-6" data-state="menu-access-error">
        <div className="fsp-eyebrow mb-2">{label}</div>
        <h1 className="mb-3 text-xl font-bold">Couldn’t load your menu access</h1>
        <p className="text-dim mb-4 text-sm leading-relaxed">
          The {label} menu is here, but we couldn’t check which parts of it you may open, so none are shown.
        </p>
        <button type="button" className="fsp-btn fsp-btn-primary px-4 py-2 text-sm" onClick={onRetry}>Try again</button>
      </div>
    </div>
  );
}
