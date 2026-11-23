import { defineConfig } from '@playwright/test';

// Runs against a deployed stack (default http://localhost:8080), like the checks on the target.
export default defineConfig({
  testDir: 'e2e',
  timeout: 6 * 60_000,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'e2e-report' }]],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:8080',
    viewport: { width: 1600, height: 900 },
    browserName: 'chromium',
    headless: true,
  },
});
