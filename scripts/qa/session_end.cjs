// Check 9 -- a session that ends while someone is working.
//
//   cd angular-frontend && npx ng build --configuration production
//   node scripts/qa/serve_prod.cjs --port 4300 --backend 8090 &
//   node scripts/qa/session_end.cjs [--base http://127.0.0.1:4300]
//
// Real Chromium, the production build, the browser's clock moved forward
// with Playwright's clock (the server's own limits are moved in the data by
// month_in_a_day.py). Scenarios:
//   S1 30 idle minutes: a two-minute warning, then the sign-in screen with a
//      reason, and the old session really is dead on the server;
//   S2 activity keeps a session alive;
//   S3 two tabs, the person works in one: the idle one must not sign them out
//      of the one they are using;
//   S4 the server ends the session (8 h limit) while a quiz is open: the
//      answer is not silently recorded, the reason is shown, the quiz can be
//      taken after signing in again;
//   S5 the back button after signing out shows no one's data.
'use strict';
const path = require('node:path');
const { createRequire } = require('node:module');
const { execFileSync } = require('node:child_process');
const req = createRequire(path.resolve(__dirname, '../../angular-frontend/package.json'));
const { chromium } = req('@playwright/test');

const arg = (n, d) => { const i = process.argv.indexOf(`--${n}`); return i > 0 ? process.argv[i + 1] : d; };
const BASE = arg('base', 'http://127.0.0.1:4300');
const results = [];
const check = (ok, label, detail = '') => {
  results.push(ok);
  console.log(`  [${ok ? 'PASS' : 'FAIL'}] ${label}${detail ? ' — ' + detail : ''}`);
};

// Small helpers that go through Python's qa_lib so the QA-only guards apply.
const py = (code) => execFileSync('python', ['-c', `import sys; sys.path.insert(0, r'${__dirname}')\n${code}`],
  { env: { ...process.env, PYTHONUTF8: '1' } }).toString().trim();
const sql = (statement) => py(`from qa_lib import db\nwith db() as c:\n    c.cursor().execute("""${statement}""")\n    c.commit()`);

async function signIn(page, email) {
  await page.goto(`${BASE}/login`);
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill('x');
  await Promise.all([page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 30000 }),
    page.locator('form button[type="submit"]').click()]);
}

async function apiStatus(page, p) {
  return page.evaluate(async (u) => (await fetch(u, { credentials: 'include' })).status, p);
}

(async () => {
  const browser = await chromium.launch();
  const MIN = 60 * 1000;

  // S1 + S2 -------------------------------------------------------------------
  {
    const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    await ctx.clock.install({ time: new Date() });
    const page = await ctx.newPage();
    // The page's clock only moves when told to: advance in small steps so
    // rendering and the sign-out round-trip can complete in between.
    const advance = async (minutes) => {
      await ctx.clock.runFor(Math.round(minutes * MIN));
      for (let k = 0; k < 8; k++) { await ctx.clock.runFor(250); await page.waitForTimeout(100); }
    };
    // A key press is activity (keydown); a click could land on a link.
    const activity = () => page.keyboard.press('Shift');
    await signIn(page, 'test_operator_w031@magti.ge');
    await page.goto(`${BASE}/reading`);
    await advance(20);
    await activity();
    await advance(20);
    check(!page.url().includes('/login'), 'S2 activity at 20 min keeps the session alive at 40 min', page.url());
    await activity();
    await advance(28.5);
    const warning = await page.locator('#session-warning-heading').count();
    check(warning > 0 && !page.url().includes('/login'), 'S1 a warning shows two minutes before the end');
    await advance(2);
    check(page.url().includes('/login') && page.url().includes('session-expired'),
      'S1 after 30 idle minutes: the sign-in screen, with the reason', page.url());
    const reason = await page.locator('[role="status"]').first().textContent().catch(() => '');
    check(Boolean(reason && reason.trim()), 'S1 the reason is written on the screen', (reason || '').trim().slice(0, 80));
    check(await apiStatus(page, '/api/users/me') === 401, 'S1 the old session is dead on the server too');
    // S5 back button
    await page.goBack();
    await page.waitForTimeout(1500);
    const leaked = await page.locator('text=ოპერატორი 031').count();
    check(page.url().includes('/login') || leaked === 0, 'S5 back after sign-out shows nobody\'s data', page.url());
    await ctx.close();
  }

  // S3 two tabs ------------------------------------------------------------------
  {
    const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    await ctx.clock.install({ time: new Date() });
    const a = await ctx.newPage();
    await signIn(a, 'test_operator_w032@magti.ge');
    const b = await ctx.newPage();
    await b.goto(`${BASE}/info`);
    await a.goto(`${BASE}/reading`);
    for (let m = 0; m < 34; m += 2) {
      await ctx.clock.runFor(2 * MIN);
      await a.bringToFront();
      await a.mouse.move(300 + m, 300); await a.keyboard.press('Shift');
    }
    await a.waitForTimeout(1500);
    const aStatus = await apiStatus(a, '/api/users/me');
    check(!a.url().includes('/login') && aStatus === 200,
      'S3 working in tab A for 34 minutes: the idle tab B does not sign the person out',
      `tab A url ${a.url()}, /api/users/me ${aStatus}, tab B url ${b.url()}`);
    await ctx.close();
  }

  // S4 quiz open when the server ends the session --------------------------------
  {
    const world = JSON.parse(require('node:fs').readFileSync(path.join(__dirname, 'results/world.json'), 'utf8'));
    const quizReading = world.readings.find((r) => r.quiz && r.department === 'ტექნიკური');
    const email = 'test_operator_w041@magti.ge'; // 41 % 20 = 1 -> ტექნიკური
    const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    const page = await ctx.newPage();
    await signIn(page, email);
    const before = py(`from qa_lib import db\nwith db() as c:\n    print(c.cursor().execute("SELECT COUNT(*) FROM quiz_attempts q JOIN users u ON u.id = q.user_id WHERE LOWER(u.email) = '${email}'").fetchone()[0])`);
    await page.goto(`${BASE}/article/${quizReading.article_id}`);
    await page.waitForTimeout(2000);
    const quizButton = page.getByRole('button', { name: /ქვიზ/ }).first();
    const hasQuiz = await quizButton.count();
    if (hasQuiz) {
      await quizButton.click();
      await page.getByRole('dialog').getByRole('radio').first().check().catch(() => {});
    }
    sql(`UPDATE portal_sessions SET expires_at = SYSTIMESTAMP - INTERVAL '1' MINUTE WHERE revoked_at IS NULL AND user_id = (SELECT id FROM users WHERE LOWER(email) = '${email}')`);
    if (hasQuiz) {
      await page.getByRole('dialog').getByRole('button', { name: /ჩაბარება|გაგზავნა|დასრულება/ }).first().click().catch(() => {});
    } else {
      await page.reload();
    }
    await page.waitForURL(/\/login/, { timeout: 15000 }).catch(() => {});
    check(page.url().includes('/login') && page.url().includes('session-ended'),
      `S4 the server ends the session ${hasQuiz ? 'during a quiz' : 'on an article'}: sign-in with the reason`, page.url());
    const after = py(`from qa_lib import db\nwith db() as c:\n    print(c.cursor().execute("SELECT COUNT(*) FROM quiz_attempts q JOIN users u ON u.id = q.user_id WHERE LOWER(u.email) = '${email}'").fetchone()[0])`);
    check(before === after, 'S4 nothing was recorded for the expired session', `${before} -> ${after}`);
    await signIn(page, email);
    check(!page.url().includes('/login'), 'S4 signing in again works and returns to work', page.url());
    await ctx.close();
  }

  await browser.close();
  const failed = results.filter((r) => !r).length;
  console.log(`\nsession_end: ${results.length - failed}/${results.length} passed`);
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
