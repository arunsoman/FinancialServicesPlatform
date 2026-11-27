// Portal boot imports: self-hosted Figtree + the one theme. Every portal
// entry imports this first (offline-safe fonts, plans §5.4).
import '@fontsource/figtree/latin-400.css'
import '@fontsource/figtree/latin-600.css'
import '@fontsource/figtree/latin-800.css'
import '@/index.css'
import { installSubmitGuard } from '@/portals/shared/web-core/submit-guard'

// One press = one action, page-wide: no button/form/mutation can double-fire.
installSubmitGuard()
