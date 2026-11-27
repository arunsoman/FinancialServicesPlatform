/**
 * A portal with NO published layout has no menu — and says so. Menus come only from the
 * published layout (portal builder → menu publisher → repo seed → deploy); there is no
 * build-time fallback menu. Nothing was published for this portal.
 */
import type { ReactNode } from 'react';

export interface NoMenuConfiguredProps {
  /** the portal's display name (PortalConfig.label) */
  label: string;
}

export function NoMenuConfigured({ label }: NoMenuConfiguredProps): ReactNode {
  return (
    <div role="status" className="grid h-[100dvh] place-items-center px-4">
      <div className="fsp-panel max-w-lg p-6" data-state="no-menu">
        <div className="fsp-eyebrow mb-2">{label}</div>
        <h1 className="mb-3 text-xl font-bold">No menu configured for this portal</h1>
        <p className="text-dim text-sm leading-relaxed">
          Nobody has published a layout for the {label} portal yet, so there is nothing to show.
          A platform administrator builds it in the widget workbench’s portal builder; it appears
          here once a second administrator approves it.
        </p>
      </div>
    </div>
  );
}
