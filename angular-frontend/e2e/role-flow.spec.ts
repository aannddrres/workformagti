import { Browser, Page, expect, test } from '@playwright/test';
import { apiLogin, firstCategoryId, runId, seedTokenIntoPage } from './helpers';

/**
 * One mandatory article, handed from role to role in real browsers, each
 * person in their own session: the content administrator publishes it, the
 * addressed operator finds and confirms it, an operator of another
 * department never meets it, and the system administrator's progress table
 * counts the confirmation on its next load.
 *
 * The same chain on every API screen, with groups, deadlines, edits,
 * archiving and quizzes, is RoleFlowIntegrationTest (Java). This spec proves
 * the screens draw what those endpoints say.
 */
const WEEK_AHEAD = () => new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString();

async function pageAs(browser: Browser, token: string): Promise<Page> {
  const page = await (await browser.newContext({ viewport: { width: 1920, height: 1080 } })).newPage();
  await seedTokenIntoPage(page, token);
  return page;
}

async function progressRow(page: Page, name: string): Promise<{ read: number; total: number }> {
  await page.goto('/admin/overview');
  const row = page.locator('tr', { hasText: name });
  await expect(row).toBeVisible();
  const cells = row.locator('td');
  return { read: Number(await cells.nth(2).innerText()), total: Number(await cells.nth(3).innerText()) };
}

test('a published mandatory article: the operator confirms it and the admin counts it', async ({ browser, request }) => {
  test.setTimeout(120_000);
  const id = runId();
  const contentToken = await apiLogin(request, 'content@magti.ge');
  const adminToken = await apiLogin(request, 'admin@magti.ge');
  const techToken = await apiLogin(request, 'tech@magti.ge');
  const infoToken = await apiLogin(request, 'info@magti.ge');
  const techName = (await (await request.get('/api/users/me', {
    headers: { Authorization: `Bearer ${techToken}` }
  })).json()).name as string;

  const title = `E2E როლების ნაკადი ${id}`;
  const created = await request.post('/api/articles/command', {
    headers: { Authorization: `Bearer ${contentToken}` },
    data: {
      article: {
        title,
        content: '<p>ყველა ოპერატორმა უნდა გაეცნოს.</p>',
        category_id: await firstCategoryId(request, contentToken),
        target_departments: ['ტექნიკური'],
        status: 'published',
        is_draft: false,
        quiz_enabled: false
      },
      mandatory: true,
      due_date: WEEK_AHEAD()
    }
  });
  expect(created.ok(), `publish failed: ${created.status()} ${await created.text()}`).toBeTruthy();
  const articleId = (await created.json()).id as number;

  const admin = await pageAs(browser, adminToken);
  const before = await progressRow(admin, techName);

  // Another department: not in its knowledge base, not owed, not openable.
  const info = await pageAs(browser, infoToken);
  await info.goto(`/info?q=${encodeURIComponent(id)}`);
  await expect(info.getByText(title)).toHaveCount(0);
  await info.goto('/reading');
  await expect(info.getByRole('link', { name: title })).toHaveCount(0);
  await info.goto(`/article/${articleId}`);
  await expect(info.getByRole('heading', { name: title })).toHaveCount(0);

  // The addressed operator: in the knowledge base and owed, at once.
  const tech = await pageAs(browser, techToken);
  await tech.goto(`/info?q=${encodeURIComponent(id)}`);
  await expect(tech.getByText(title)).toBeVisible();
  await tech.goto('/reading');
  const row = tech.getByRole('link', { name: title });
  await expect(row).toBeVisible();
  await row.click();
  await expect(tech).toHaveURL(new RegExp(`/article/${articleId}$`));
  const confirm = tech.locator('app-reading-confirm');
  await confirm.getByRole('button', { name: 'წავიკითხე' }).click();
  await expect(confirm.getByText('გაცნობა დადასტურებულია')).toBeVisible();
  await tech.goto('/reading');
  await expect(tech.getByRole('link', { name: title }).getByText('წაკითხულია', { exact: true })).toBeVisible();

  // The system admin's progress table, on its next load.
  const after = await progressRow(admin, techName);
  expect(after).toEqual({ read: before.read + 1, total: before.total });
});
