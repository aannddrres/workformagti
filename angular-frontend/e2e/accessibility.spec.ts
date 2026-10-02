import AxeBuilder from '@axe-core/playwright';
import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { apiLogin, firstCategoryId, runId, seedTokenIntoPage } from './helpers';

/**
 * Every screen, as the person who uses it, in both themes, through axe-core's
 * WCAG 2.1 A/AA rules: names on controls, labels on fields, contrast, landmark
 * and heading structure, ARIA used as the spec allows.
 *
 * A screen fails on any violation that is not in KNOWN below. KNOWN is the
 * list of what has been looked at and deliberately left, each with its reason
 * -- empty is the goal, and an entry is never added to make this pass.
 */
const KNOWN: Record<string, string> = {};

type Screen = { persona: string; path: string; ready?: (page: Page) => Promise<void> };

const WEEK_AHEAD = () => new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString();

async function seedContent(request: APIRequestContext): Promise<{ articleId: number; newsId: number; videoId: number }> {
  const token = await apiLogin(request, 'content@magti.ge');
  const headers = { Authorization: `Bearer ${token}` };
  const id = runId();
  const article = await request.post('/api/articles/command', {
    headers,
    data: {
      article: {
        title: `ხელმისაწვდომობა ${id}`,
        content: '<h2>სათაური</h2><p>ტექსტი <a href="https://www.magti.ge">ბმული</a>.</p><ul><li>პუნქტი</li></ul>',
        category_id: await firstCategoryId(request, token),
        target_departments: ['ტექნიკური'],
        status: 'published',
        is_draft: false,
        quiz_enabled: true
      },
      mandatory: true,
      due_date: WEEK_AHEAD(),
      quiz: {
        questions: [{
          question_text: 'რომელია სწორი?', position: 0,
          answers: [
            { answer_text: 'პირველი', is_correct: true, position: 0 },
            { answer_text: 'მეორე', is_correct: false, position: 1 }
          ]
        }]
      }
    }
  });
  expect(article.ok(), await article.text()).toBeTruthy();
  const news = await request.post('/api/news', {
    headers,
    data: { title: `სიახლე ${id}`, content: '<p>სიახლის ტექსტი</p>', target_department: 'All', is_draft: false }
  });
  expect(news.ok(), await news.text()).toBeTruthy();
  const video = await request.post('/api/videos', {
    headers,
    data: { title: `ვიდეო ${id}`, video_url: 'dQw4w9WgXcQ', target_department: 'All' }
  });
  expect(video.ok(), await video.text()).toBeTruthy();
  return { articleId: (await article.json()).id, newsId: (await news.json()).id, videoId: (await video.json()).id };
}

let ids: { articleId: number; newsId: number; videoId: number };

const screens = (): Screen[] => [
  { persona: 'tech@magti.ge', path: '/' },
  { persona: 'tech@magti.ge', path: '/info' },
  { persona: 'tech@magti.ge', path: '/reading' },
  { persona: 'tech@magti.ge', path: '/news' },
  { persona: 'tech@magti.ge', path: `/news/${ids.newsId}` },
  { persona: 'tech@magti.ge', path: '/videos' },
  { persona: 'tech@magti.ge', path: `/videos/${ids.videoId}` },
  { persona: 'tech@magti.ge', path: '/favorites' },
  { persona: 'tech@magti.ge', path: '/profile' },
  { persona: 'tech@magti.ge', path: `/article/${ids.articleId}` },
  { persona: 'tech@magti.ge', path: '/forbidden' },
  { persona: 'tech@magti.ge', path: '/no-such-page' },
  { persona: 'manager@magti.ge', path: '/manager' },
  { persona: 'content@magti.ge', path: '/admin/content' },
  { persona: 'content@magti.ge', path: '/admin/content', ready: (page) => openDrawer(page, '+ სტატია', 'app-article-edit-drawer') },
  { persona: 'content@magti.ge', path: '/admin/content', ready: (page) => openDrawer(page, '+ სიახლე', 'app-news-edit-drawer') },
  { persona: 'content@magti.ge', path: '/admin/content', ready: (page) => openDrawer(page, '+ ვიდეო', 'app-video-edit-drawer') },
  { persona: 'content@magti.ge', path: '/admin/trash' },
  { persona: 'content@magti.ge', path: '/admin/categories' },
  { persona: 'admin@magti.ge', path: '/admin/overview' },
  { persona: 'admin@magti.ge', path: '/admin/audit' },
  { persona: 'admin@magti.ge', path: '/admin/broadcasts' },
  { persona: 'admin@magti.ge', path: '/admin/exports' },
  { persona: 'admin@magti.ge', path: '/admin/access' },
  { persona: 'admin@magti.ge', path: '/admin/org' },
  { persona: 'admin@magti.ge', path: '/admin/org/assignments' },
  { persona: 'admin@magti.ge', path: '/admin/org/backfill' }
];

/** The drawers are where a content administrator spends the day. */
async function openDrawer(page: Page, item: string, drawer: string): Promise<void> {
  await page.getByRole('button', { name: 'კონტენტის ტიპის არჩევა' }).click();
  // The menu's item, not the split button's main half, which can carry the same label.
  await page.getByRole('button', { name: item, exact: true }).last().click();
  await expect(page.locator(drawer).getByRole('dialog')).toBeVisible();
}

async function scan(page: Page): Promise<string[]> {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    // YouTube's player is YouTube's markup, not ours: nothing here can change it.
    .exclude('iframe')
    .analyze();
  return results.violations.flatMap((v) =>
    v.nodes.map((n) => `${v.id} [${v.impact}] ${n.target.join(' ')} -- ${n.failureSummary?.split('\n')[1]?.trim() ?? v.help}`)
  );
}

async function settle(page: Page): Promise<void> {
  await page.waitForLoadState('networkidle');
  // Skeletons and fades: axe reads computed colours, and a half-faded row
  // reports a contrast failure that no person ever sees.
  await page.waitForTimeout(600);
}

test.beforeAll(async ({ request }) => {
  ids = await seedContent(request);
});

for (const dark of [false, true]) {
  const theme = dark ? 'dark' : 'light';

  test(`login screen meets WCAG 2.1 AA (${theme})`, async ({ page }) => {
    await page.setViewportSize({ width: 1920, height: 1080 });
    await page.addInitScript((d) => localStorage.setItem('magti_dark_mode', d ? 'true' : 'false'), dark);
    await page.goto('/login');
    await settle(page);
    const found = (await scan(page)).filter((v) => !KNOWN[v.split(' ')[0]]);
    expect(found, found.join('\n')).toEqual([]);
  });

  test(`every signed-in screen meets WCAG 2.1 AA (${theme})`, async ({ browser, request }, testInfo) => {
    test.setTimeout(240_000);
    const tokens = new Map<string, string>();
    const report: Record<string, string[]> = {};
    for (const screen of screens()) {
      if (!tokens.has(screen.persona)) tokens.set(screen.persona, await apiLogin(request, screen.persona));
      const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
      await context.addInitScript((d) => localStorage.setItem('magti_dark_mode', d ? 'true' : 'false'), dark);
      const page = await context.newPage();
      await seedTokenIntoPage(page, tokens.get(screen.persona)!);
      await page.goto(screen.path);
      await settle(page);
      if (screen.ready) {
        await screen.ready(page);
        await settle(page);
      }
      const found = (await scan(page)).filter((v) => !KNOWN[v.split(' ')[0]]);
      if (found.length) report[`${screen.persona} ${screen.path}${screen.ready ? ' (opened)' : ''}`] = found;
      await context.close();
    }
    await testInfo.attach(`axe-${theme}.json`, { body: JSON.stringify(report, null, 2), contentType: 'application/json' });
    const lines = Object.entries(report).flatMap(([where, v]) => [where, ...v.map((x) => '   ' + x)]);
    expect(lines, lines.join('\n')).toEqual([]);
  });
}

/**
 * The news and video cards were a role="button" with the favourite star's own
 * button inside it: a control in a control, which a screen reader announces
 * as one button and never reaches the star in. The title is the control now,
 * and the star its own stop -- both by keyboard alone.
 */
test('a news card and a video card open, and star, from the keyboard', async ({ page, request }) => {
  await page.setViewportSize({ width: 1920, height: 1080 });
  await seedTokenIntoPage(page, await apiLogin(request, 'tech@magti.ge'));

  for (const [list, title, detail] of [
    ['/news', `სიახლე `, /\/news\/\d+$/],
    ['/videos', `ვიდეო `, /\/videos\/\d+$/]
  ] as const) {
    await page.goto(list);
    const card = page.locator('article', { hasText: title }).first();
    const star = card.locator('app-favorite-star button');
    await star.focus();
    await expect(star).toBeFocused();
    const open = card.getByRole('button', { name: new RegExp(title) });
    await open.focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(detail);
  }
});
