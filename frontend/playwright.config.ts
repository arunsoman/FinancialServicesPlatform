import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright config — the onboarding-chain suite drives the REAL SPA portals
 * (:5173 Vite dev), the BFF (:8080) and the hosted IdP sign-in page behind
 * them, so `make infra-up && make tb-start && make backend-*` (or
 * `make stack-up` + `make frontend-dev`) must be running first.
 *
 * Run: `bun run test:e2e` (from frontend/) — one browser, one worker, strictly
 * serial, because the chain is a single story: platform → manufacturer →
 * distributor → merchant.
 */
export default defineConfig({
  testDir: './tests/e2e',
  timeout: 180_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.FSP_SPA_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    ...devices['Desktop Chrome'],
  },
});
