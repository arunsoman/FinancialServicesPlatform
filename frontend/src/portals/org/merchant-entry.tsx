import { createRoot } from 'react-dom/client'
import '../boot'
import { PortalRoot } from '../portal-app'
import { PORTAL_MERCHANT } from '../portal-config'

createRoot(document.getElementById('root')!).render(
  <PortalRoot portal={PORTAL_MERCHANT} portalId="org" />,
)
