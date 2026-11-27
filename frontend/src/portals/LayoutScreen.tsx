/**
 * A screen of the published, signature-verified layout (/p/:menuId/:screenId — SME
 * LayoutPage). Guards in the same order as coded screens: session → eligibility → the
 * screen must show at least one widget the user may see. Each placed widget renders only
 * if this build has it AND the published menus list it (signed-layout placedDef), and
 * its host still gates on the user's permissions; core re-checks every call it makes.
 */
import { Navigate, useParams } from 'react-router-dom';
import { PageHeader } from './shared/ui/atoms';
import { FullscreenSpinner } from './shared/web-core/guards';
import { useSession } from './shared/web-core/session-core';
import { LayoutGrid } from './shared/widgets/core/LayoutGrid';
import type { LayoutGridCell } from './shared/widgets/core/LayoutGrid';
import { WidgetHost } from './shared/widgets/core/host';
import { getWidget } from './shared/widgets/registry';
import { usePageSelection } from './shared/widgets/core/page-selection';
import { placedDef, screenVisible, signedSpan, useMenuAccess, useSignedLayout } from './signed/signed-layout';

export function LayoutScreen() {
  const { menuId, screenId } = useParams();
  const { status, permissions, eligible } = useSession();
  const layout = useSignedLayout();
  const access = useMenuAccess();
  const { selection, onSelect } = usePageSelection();

  if (status === 'loading') return <FullscreenSpinner label="Restoring session" />;
  if (status === 'anon') return <Navigate to="/login" replace />;
  if (eligible === undefined) return <FullscreenSpinner label="Checking portal access" />;
  if (!eligible) return <Navigate to="/no-access" replace />;
  if (!layout) return <Navigate to="/" replace />; // no published layout: coded menus apply

  const menu = layout.menus.find((m) => m.id === menuId);
  const screen = menu?.screens.find((s) => s.id === screenId);
  if (!menu || !screen || !screenVisible(layout, menu, screen, permissions, getWidget, access)) {
    return <Navigate to="/no-access" replace />;
  }

  const cells: LayoutGridCell[] = [];
  for (const placed of screen.placedWidgets) {
    const def = placedDef(layout, placed, getWidget);
    if (!def) continue;
    cells.push({
      key: placed.id,
      span: (tier) => signedSpan(def, placed, tier),
      render: () => (
        <div data-widget={def.id} className="min-w-0">
          <WidgetHost def={def} config={placed.config} selection={selection} onSelect={onSelect} />
        </div>
      ),
    });
  }

  return (
    <>
      <PageHeader title={screen.label} subtitle={menu.label} />
      <LayoutGrid cells={cells} />
    </>
  );
}
