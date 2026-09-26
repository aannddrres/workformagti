import { defineConfig } from '@playwright/test';

const baseURL = process.env.NGINX_SMOKE_BASE_URL;
const imageId = process.env.NGINX_SMOKE_IMAGE_ID;
if (!baseURL || !imageId?.startsWith('sha256:') || process.env.NGINX_SMOKE_ENV !== 'isolated') {
  throw new Error('Use scripts/verify-nginx-smoke.sh with a freshly built frontend image and an isolated backend.');
}
const url = new URL(baseURL);
if (!['localhost', '127.0.0.1'].includes(url.hostname) || ['8081', '8082', '4200', '4201'].includes(url.port)) {
  throw new Error('nginx smoke requires its own loopback port; demo/UAT and ng serve are forbidden.');
}

export default defineConfig({
  testDir: './e2e',
  testMatch: 'nginx-upload.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 120_000,
  reporter: [['list']],
  use: { baseURL, trace: 'off', screenshot: 'off', video: 'off' },
});
