import { defineConfig, devices } from '@playwright/test';

/**
 * One-shot dev setup flows that drive the REAL UI (not tests of it), e.g. `make plugin-upgrade`.
 * (Portal bundles need none: core signs the repo's menus at boot — docs/portal-menus.md.)
 */
export default defineConfig({
  testDir: './tests/setup',
  timeout: 180_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.FSP_SPA_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    ...devices['Desktop Chrome'],
    // the builder docks its inspector beside the canvas only on a wide desktop (≥1500px);
    // narrower, it floats over the canvas and covers the publish controls
    viewport: { width: 1680, height: 1000 },
  },
});
