// Check 14 -- the network drops while someone is working.
//
//   node scripts/qa/offline_check.cjs [--base http://127.0.0.1:4300]
//
// Against the production build (serve_prod.cjs). Signs in, opens one page,
// goes offline, then (1) moves to a page whose code has not been loaded yet,
// (2) reloads data on the page already open, and reports what the person
// sees in each case. Round 4 found (1) silently doing nothing; the owner
// postponed a fix, so this records the state rather than failing on it.
'use strict';
const path = require('node:path');
const { createRequire } = require('node:module');
const req = createRequire(path.resolve(__dirname, '../../angular-frontend/package.json'));
const { chromium } = req('@playwright/test');

const arg = (n, d) => { const i = process.argv.indexOf(`--${n}`); return i > 0 ? process.argv[i + 1] : d; };
const BASE = arg('base', 'http://127.0.0.1:4300');

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message.slice(0, 120)));
  await page.goto(`${BASE}/login`);
  await page.locator('#login-email').fill('test_operator_w051@magti.ge');
  await page.locator('#login-password').fill('x');
  await Promise.all([page.waitForURL((u) => !u.pathname.startsWith('/login')), page.locator('form button[type="submit"]').click()]);
  await page.goto(`${BASE}/reading`);
  await page.waitForLoadState('networkidle');

  await ctx.setOffline(true);
  const before = page.url();
  await page.locator('a[href="/videos"]').first().click().catch(() => {});
  await page.waitForTimeout(3000);
  const moved = page.url() !== before;
  const visible = await page.locator('[role="alert"], [role="status"]').allTextContents();
  console.log(`  [INFO] offline, click to a not-yet-loaded page: url ${moved ? 'changed' : 'unchanged'} (${page.url()}); `
    + `messages shown: ${JSON.stringify(visible.map((t) => t.trim()).filter(Boolean)).slice(0, 200)}; page errors: ${errors.length}`);

  await page.goto(`${BASE}/reading`).catch((e) => console.log(`  [INFO] offline full reload: ${e.message.split('\n')[0]}`));
  await ctx.setOffline(false);
  await page.goto(`${BASE}/reading`);
  await page.waitForLoadState('networkidle');
  const back = !page.url().includes('/login');
  console.log(`  [${back ? 'PASS' : 'FAIL'}] back online: still signed in and working (${page.url()})`);
  await browser.close();
  process.exit(back ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(2); });
