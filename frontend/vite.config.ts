import { configDefaults, defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { resolve, dirname } from 'path'
import { fileURLToPath } from 'url'
import { widgetPackPlugin } from './tools/widget-pack/vite-plugin'

const R = dirname(fileURLToPath(import.meta.url))

/**
 * One workspace, five org/admin build identities + a chooser — the Vite equivalent of
 * the SME angular.json fileReplacements pattern (plans/ui-portals-plan.md §2.3).
 * Dev: / serves the chooser; /admin/**, /manufacturer/**, /distributor/**,
 * /wholesaler/, /merchant/ boot their own app with its own PortalConfig identity.
 */
const PORTALS = ['admin', 'manufacturer', 'distributor', 'wholesaler', 'merchant', 'widget-workbench']

/** Maps /<portal>/ to that entry HTML in dev + preview (build uses rollupOptions.input). */
const portalEntries = () => ({
  name: 'fsp-portal-entries',
  configureServer(server: import('vite').ViteDevServer) {
    server.middlewares.use((req, _res, next) => {
      const m = req.url?.match(new RegExp(`^/(${PORTALS.join('|')})([/?].*)?$`))
      if (m) req.url = `/${m[1]}.html`
      next()
    })
  },
  configurePreviewServer(server: import('vite').PreviewServer) {
    server.middlewares.use((req, _res, next) => {
      const m = req.url?.match(new RegExp(`^/(${PORTALS.join('|')})([/?].*)?$`))
      if (m) req.url = `/${m[1]}.html`
      next()
    })
  },
})

export default defineConfig({
  plugins: [portalEntries(), react(), widgetPackPlugin()],
  resolve: { alias: { '@': resolve(R, 'src') } },
  server: {
    port: 5173,
    proxy: {
      '/bff': { target: 'http://localhost:8080', changeOrigin: true },
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  // Playwright specs (tests/e2e, tests/setup) drive the live stack; vitest owns unit tests only
  test: { exclude: [...configDefaults.exclude, 'tests/e2e/**', 'tests/setup/**'] },
  build: {
    outDir: 'dist',
    sourcemap: true,
    rollupOptions: {
      input: {
        chooser: resolve(R, 'index.html'),
        admin: resolve(R, 'admin.html'),
        manufacturer: resolve(R, 'manufacturer.html'),
        distributor: resolve(R, 'distributor.html'),
        wholesaler: resolve(R, 'wholesaler.html'),
        merchant: resolve(R, 'merchant.html'),
        'widget-workbench': resolve(R, 'widget-workbench.html'),
      },
    },
  },
})
