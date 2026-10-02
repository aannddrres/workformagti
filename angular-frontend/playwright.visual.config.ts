import { defineConfig } from '@playwright/test';
import base from './playwright.config';

/**
 * e2e/visual-compare.spec.ts alone, with its "expected" images written fresh
 * on every run into test-results/ (gitignored) -- see that spec for why no
 * baseline image is ever committed. Started by scripts/visual-diff.sh.
 */
export default defineConfig({
  ...base,
  testIgnore: [],
  testMatch: 'visual-compare.spec.ts',
  snapshotPathTemplate: 'test-results/visual-baseline/{arg}{ext}',
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report/visual' }]],
  timeout: 90_000
});
