import { defineConfig, devices } from '@playwright/test';

/**
 * Targets the isolated Java+Oracle test instance (Angular dev server on
 * :4201, proxying /api to Spring Boot on :8090) -- never the production
 * pair on :4200/:8080. Both must already be running; this config does not
 * start them (webServer omitted on purpose, see docs/JAVA_ORACLE_ANGULAR_MIGRATION.md
 * Test 3 Tier B write-up).
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 30_000,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:4201',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ]
});
