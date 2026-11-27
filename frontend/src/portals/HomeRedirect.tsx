import { Navigate } from 'react-router-dom';
import { FullscreenSpinner } from './shared/web-core/guards';
import { useSession } from './shared/web-core/session-core';
import { getWidget } from './shared/widgets/registry';
import { firstVisibleLink, useMenuAccess, useSignedLayout } from './signed/signed-layout';

/**
 * Portal home: the first screen of the PUBLISHED layout the user can see (SignedMenuGate has
 * already shown "No menu configured" when there is none). Never a coded menu point, never a
 * hard-coded route — a portal without that point would loop.
 */
export function HomeRedirect() {
  const { status, permissions } = useSession();
  const layout = useSignedLayout();
  const access = useMenuAccess();
  if (status === 'loading') return <FullscreenSpinner label="Restoring session" />;
  const target = layout ? firstVisibleLink(layout, permissions, getWidget, access) : null;
  return <Navigate to={target ?? '/no-access'} replace />;
}
