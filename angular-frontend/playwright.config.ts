import { defineConfig, devices } from '@playwright/test';

/**
 * Locally: the isolated Java+Oracle test instance (Angular dev server on
 * :4201, proxying /api to Spring Boot on :8090) -- never the developer's
 * working pair on :4200/:8080. Both must already be running; this config
 * does not start them.
 *
 * In CI: E2E_BASE_URL points at the stack the workflow brings up itself, so
 * the same specs run against a real Oracle on every push. They had never
 * been run before that job existed -- docs/TEST_PLAN_AND_RESULTS.md still
 * records Test 3 as not started, and the reason was always the setup cost,
 * not the specs.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 30_000,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:4201',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: process.env.CI ? 'retain-on-failure' : 'off'
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // The runner image ships a Chromium that the pinned Playwright may
        // not match; PLAYWRIGHT_CHROMIUM_PATH lets the workflow point at the
        // one it installed instead of failing on a version mismatch.
        ...(process.env.PLAYWRIGHT_CHROMIUM_PATH
          ? { launchOptions: { executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH } }
          : {})
      }
    }
  ]
});
