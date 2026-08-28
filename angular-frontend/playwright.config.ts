import { defineConfig, devices } from '@playwright/test';

const reportDirectory = process.env.PLAYWRIGHT_REPORT_DIR ?? 'playwright-report';

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
  // Logs the shared personas in once for the whole run. Without it every
  // spec that needs admin@magti.ge spends one of that account's ten logins
  // per minute (LoginRateLimiter.java:70) and the suite starts failing on
  // 429s as it grows -- which would look like broken tests.
  globalSetup: './e2e/global-setup.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 30_000,
  // The json reporter is what the workflow's "Which specs failed" step reads:
  // Playwright's own failure block lands ~230 lines above the end of the job
  // log, under two server-log dumps, so pulling it back from the API means
  // pulling the whole backend startup with it.
  reporter: process.env.CI || process.env.PLAYWRIGHT_REPORT_DIR
    ? [
        ['list'],
        ['html', { open: 'never', outputFolder: reportDirectory }],
        ['json', { outputFile: `${reportDirectory}/results.json` }]
      ]
    : [['list']],
  use: {
    // Playwright's default here is 0 -- no limit -- so a click on an element
    // that never appears is bounded only by the TEST timeout. Three separate
    // debugging rounds on this suite were spent on that: the run burns two or
    // three minutes and reports "Test timeout exceeded", naming the line but
    // never the element or the reason, and the actual call log only survives
    // in the html report. With a bound, the same miss fails in 15s and says
    // what it was waiting for.
    //
    // 15s rather than the 5s expect timeout: these specs drive a dev-server
    // build against a real Oracle, and a first-paint click after a route
    // change is legitimately slow on a cold CI runner.
    actionTimeout: 15_000,
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
