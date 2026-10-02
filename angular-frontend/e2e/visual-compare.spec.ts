import { APIRequestContext, Browser, Locator, Page, expect, test } from '@playwright/test';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { apiLogin, seedTokenIntoPage } from './helpers';

/**
 * Screen-by-screen comparison of two builds of the frontend against ONE
 * backend, at the same moment: VISUAL_BASELINE_URL serves the known-good
 * build, the usual baseURL the change. Same data, same clock, so a difference
 * is the change and nothing else -- the reason this does not keep committed
 * baseline images, which would differ by data, date and operating system
 * before any code changed.
 *
 * Not part of the normal suite (playwright.config.ts ignores it); run it
 * through scripts/visual-diff.sh, which starts the baseline build from a git
 * ref. A failure leaves expected / actual / diff images per screen in the
 * html report.
 */
const BASELINE = process.env.VISUAL_BASELINE_URL;

/**
 * live: what changes between the two photographs on its own -- the audit
 * trail gains a row for every visit, activity counts tick -- is painted over
 * in both, so the frame around it is still compared.
 */
type Screen = {
  name: string; persona: string; path: string | ((ids: Ids) => string);
  open?: (page: Page) => Promise<void>; live?: (page: Page) => Locator[];
};
type Ids = { article: number; news: number; video: number };

const SCREENS: Screen[] = [
  { name: 'operator-home', persona: 'tech@magti.ge', path: '/' },
  { name: 'operator-knowledge-base', persona: 'tech@magti.ge', path: '/info' },
  { name: 'operator-reading', persona: 'tech@magti.ge', path: '/reading' },
  { name: 'operator-news', persona: 'tech@magti.ge', path: '/news' },
  { name: 'operator-news-item', persona: 'tech@magti.ge', path: (i) => `/news/${i.news}` },
  { name: 'operator-videos', persona: 'tech@magti.ge', path: '/videos' },
  { name: 'operator-article', persona: 'tech@magti.ge', path: (i) => `/article/${i.article}` },
  { name: 'operator-favorites', persona: 'tech@magti.ge', path: '/favorites' },
  { name: 'operator-profile', persona: 'tech@magti.ge', path: '/profile' },
  { name: 'manager-team', persona: 'manager@magti.ge', path: '/manager' },
  { name: 'content-list', persona: 'content@magti.ge', path: '/admin/content' },
  {
    name: 'content-article-drawer', persona: 'content@magti.ge', path: '/admin/content',
    open: async (page) => {
      await page.getByRole('button', { name: 'კონტენტის ტიპის არჩევა' }).click();
      await page.getByRole('button', { name: '+ სტატია', exact: true }).last().click();
      await expect(page.locator('app-article-edit-drawer').getByRole('dialog')).toBeVisible();
    }
  },
  { name: 'content-trash', persona: 'content@magti.ge', path: '/admin/trash' },
  { name: 'content-categories', persona: 'content@magti.ge', path: '/admin/categories' },
  {
    name: 'admin-overview', persona: 'admin@magti.ge', path: '/admin/overview',
    live: (page) => ['ბოლო ადმინისტრაციული ქმედებები', 'სისტემური აქტივობა', 'ხშირი ძიებები', 'უშედეგო ძიებები']
      .map((heading) => page.locator('.surface-card', { has: page.getByRole('heading', { name: heading }) }))
  },
  { name: 'admin-audit', persona: 'admin@magti.ge', path: '/admin/audit', live: (page) => [page.locator('tbody'), page.locator('div.mt-4.justify-between.text-xs > span')] },
  { name: 'admin-broadcasts', persona: 'admin@magti.ge', path: '/admin/broadcasts' },
  { name: 'admin-exports', persona: 'admin@magti.ge', path: '/admin/exports' },
  { name: 'admin-access', persona: 'admin@magti.ge', path: '/admin/access' },
  { name: 'admin-org', persona: 'admin@magti.ge', path: '/admin/org' }
];

async function firstVisibleIds(request: APIRequestContext, token: string): Promise<Ids> {
  const headers = { Authorization: `Bearer ${token}` };
  const first = async (path: string) => {
    const body = await (await request.get(path, { headers })).json();
    const items = Array.isArray(body) ? body : body.items;
    expect(items?.length, `${path} is empty -- seed some content first`).toBeGreaterThan(0);
    return items[0].id as number;
  };
  return { article: await first('/api/articles'), news: await first('/api/news'), video: await first('/api/videos') };
}

/**
 * A dev server intermittently answers 403 for public/i18n/*.json (the Vite
 * fs.allow trap in angular-frontend/AGENTS.md), and the screen then shows
 * raw keys -- nav.sidebar.home -- which would read as a change. One reload
 * is allowed; a second miss is the build's fault and is reported as such.
 */
async function translated(page: Page, origin: string): Promise<void> {
  const rawKey = /\b(nav|users|articles|news|audit|common)\.[a-z_]+\.[a-z_]+\b/;
  const clean = async () => !rawKey.test(await page.locator('body').innerText());
  if (await clean()) return;
  await page.reload();
  await page.waitForLoadState('networkidle');
  expect(await clean(), `${origin} rendered translation keys instead of text`).toBe(true);
}

async function shoot(browser: Browser, origin: string, token: string, path: string, dark: boolean,
                     screen: Screen): Promise<Buffer> {
  const context = await browser.newContext({ baseURL: origin, viewport: { width: 1920, height: 1080 } });
  await context.addInitScript((d) => localStorage.setItem('magti_dark_mode', d ? 'true' : 'false'), dark);
  const page = await context.newPage();
  await seedTokenIntoPage(page, token);
  await page.goto(path);
  await page.waitForLoadState('networkidle');
  await translated(page, origin);
  if (screen.open) await screen.open(page);
  await page.waitForTimeout(500);
  const shot = await page.screenshot({ fullPage: true, animations: 'disabled', caret: 'hide', mask: screen.live?.(page) ?? [] });
  await context.close();
  return shot;
}

test.skip(!BASELINE, 'set VISUAL_BASELINE_URL (scripts/visual-diff.sh does)');

for (const dark of [false, true]) {
  for (const screen of SCREENS) {
    const name = `${screen.name}-${dark ? 'dark' : 'light'}`;
    test(name, async ({ browser, request, baseURL }, testInfo) => {
      const token = await apiLogin(request, screen.persona);
      const path = typeof screen.path === 'string' ? screen.path : screen.path(await firstVisibleIds(request, token));
      const before = await shoot(browser, BASELINE!, token, path, dark, screen);
      const after = await shoot(browser, baseURL!, token, path, dark, screen);
      // The baseline becomes this run's "expected" image, so the comparison,
      // its tolerance and its diff picture are Playwright's own.
      const expected = testInfo.snapshotPath(`${name}.png`);
      mkdirSync(dirname(expected), { recursive: true });
      writeFileSync(expected, before);
      expect(after).toMatchSnapshot(`${name}.png`, { maxDiffPixels: 0 });
    });
  }
}
