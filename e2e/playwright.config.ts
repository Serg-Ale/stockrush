import { defineConfig } from '@playwright/test';

/** Runs against the whole system: `docker compose up --build --wait` first, then `npm test` here. */
export default defineConfig({
  testDir: 'tests',
  // The tests share one database and reset the same product's stock, so they must not overlap.
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8000',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
});
