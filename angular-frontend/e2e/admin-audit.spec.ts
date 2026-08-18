import { test, expect, APIRequestContext } from '@playwright/test';
import { apiLogin, createArticle, createCategory, runId, seedTokenIntoPage } from './helpers';

/**
 * The audit log: its filters, its pager, its detail drawer, the per-row
 * integrity check and the CSV export.
 *
 * This is the screen a compliance auditor is pointed at, so "the filter did
 * not crash" is worth nothing here -- what matters is that a filter which
 * should exclude a row does exclude it, and that the integrity check
 * actually asks the server.
 *
 * FIXTURE: audit rows are not written for every operation. Creating an
 * article writes none; ARCHIVE, UNARCHIVE, VERIFY and RESTORE each write one
 * (ArticleController.java:555, 579, 716, 949). So one article flipped in and
 * out of the archive is the cheapest way to produce a known number of rows
 * with a known action and category -- ARCHIVE on an article classifies as
 * CONTENT (AuditCategoryClassifier.java:23-25).
 */
const PAGE_SIZE = 50;      // admin-audit-page.ts:15

/** Flip one article in and out of the archive until it has written `rows`
 *  audit entries. Each call writes exactly one. */
async function writeAuditRows(
  request: APIRequestContext,
  token: string,
  articleId: number,
  rows: number
): Promise<void> {
  const headers = { Authorization: `Bearer ${token}` };
  for (let i = 0; i < rows; i++) {
    const action = i % 2 === 0 ? 'archive' : 'unarchive';
    const res = await request.post(`/api/articles/${articleId}/${action}`, { headers });
    expect(res.ok(), `${action} failed: ${res.status()}`).toBeTruthy();
  }
}

test.describe('audit log', () => {
  test('filters, presets and the pager', async ({ page, request }) => {
    test.setTimeout(120_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    const category = await createCategory(request, token, `E2E აუდიტის კატეგორია ${id}`);
    const articleId = await createArticle(request, token, {
      title: `E2E აუდიტის სტატია ${id}`,
      categoryId: category.id,
      targetDepartments: ['საინფორმაციო']
    });
    // One more than a page, so "next" has somewhere to go.
    await writeAuditRows(request, token, articleId, PAGE_SIZE + 2);

    await seedTokenIntoPage(page, token);
    await page.goto('/admin/audit');

    const archiveRows = page.locator('tr', { hasText: 'ARCHIVE' });
    await expect(archiveRows.first()).toBeVisible();

    // --- the pager ---------------------------------------------------------
    // Narrowed to the fixture's own action first, and not for tidiness:
    // READING the audit log writes an audit row of its own
    // (AuditLogController.java:115, VIEW_AUDIT_LOG). The unfiltered list is
    // therefore a moving target -- every page change pushes a fresh row onto
    // the top of page 1 -- and the first version of this test failed on
    // exactly that, comparing page 1's top row before and after and finding
    // it two seconds newer. ARCHIVE rows are only written by the fixture, so
    // this list holds still while the pager is exercised.
    const search = page.getByPlaceholder('ძებნა… actor:admin category:SECURITY');
    await search.fill('action:ARCHIVE');

    // Wait for the FILTER to land before touching the pager, and wait on the
    // rows rather than on a timer. Typing debounces 300ms and then calls
    // reload(), which sets offset back to 0 (admin-audit-page.ts:154-157) --
    // so a pager click issued before that debounce fires is undone by it, and
    // "წინა" stays disabled for the rest of the test. That is exactly how the
    // first version of this spec spent its whole 120s budget retrying a click
    // on a button that could never become enabled.
    //
    // "every row is an ARCHIVE row" is the precise signal: the unfiltered list
    // is a mix (VIEW_AUDIT_LOG, CREATE, LOGIN), so this count only reaches a
    // full page once the filtered response has replaced it.
    const firstPage = page.locator('tbody tr');
    await expect(firstPage).toHaveCount(PAGE_SIZE);
    await expect(archiveRows).toHaveCount(PAGE_SIZE);

    // The row's TIMESTAMP, not its whole rendering. Comparing the full row
    // text made this assertion depend on every cell staying byte-identical
    // across a re-render, which is more than "the pager came back to the
    // same entries" needs to mean -- and it failed on a cell other than the
    // one that identifies the row. The timestamp is the identity that is
    // actually visible.
    const topStamp = () => page.locator('tbody tr').first().locator('td').first().innerText();
    const page1Top = await topStamp();

    const previous = page.getByRole('button', { name: 'წინა' });
    await expect(previous).toBeDisabled();

    await page.getByRole('button', { name: 'შემდეგი' }).click();
    // Enabled first: "წინა" is disabled at offset 0, so this is the pager's own
    // statement that it left page 1. Asserting it here turns a page that never
    // moved into a 5s failure instead of a 120s timeout spent clicking a button
    // that is never going to accept the click.
    await expect(previous).toBeEnabled();
    // And a different page means different ROWS, not merely a different state
    // on the pager -- so this compares what is actually in the table.
    await expect.poll(topStamp).not.toBe(page1Top);

    await previous.click();
    await expect.poll(topStamp).toBe(page1Top);

    await search.fill('');

    // --- the category filter ----------------------------------------------
    // ARCHIVE on an article is CONTENT, so SECURITY must exclude every one of
    // them. The table will not be empty (logins write SECURITY rows), which
    // is exactly why the assertion is on the ARCHIVE rows and not on a count.
    const categorySelect = page.locator('select');
    await categorySelect.selectOption('SECURITY');
    await expect(archiveRows).toHaveCount(0);

    await categorySelect.selectOption('CONTENT');
    await expect(archiveRows.first()).toBeVisible();

    // --- date presets and the date inputs ---------------------------------
    const startDate = page.locator('input[type="date"]').first();
    const endDate = page.locator('input[type="date"]').nth(1);

    await page.getByRole('button', { name: '30 დღე' }).click();
    await expect(startDate).not.toHaveValue('');
    await expect(archiveRows.first()).toBeVisible();

    await page.getByRole('button', { name: 'დღეს' }).click();
    // Today's preset sets both ends to today, and the fixture was written
    // seconds ago, so it must survive.
    await expect(startDate).toHaveValue(await endDate.inputValue());
    await expect(archiveRows.first()).toBeVisible();

    await page.getByRole('button', { name: '7 დღე' }).click();
    await expect(archiveRows.first()).toBeVisible();

    // A window that begins after the rows were written has to empty the table.
    await startDate.fill('2030-01-01');
    await endDate.fill('2030-12-31');
    await expect(page.getByText('ლოგები არ მოიძებნა')).toBeVisible();

    // --- search ------------------------------------------------------------
    await startDate.fill('');
    await endDate.fill('');
    await search.fill('category:SECURITY');
    await expect(archiveRows).toHaveCount(0);

    await search.fill('');
    await expect(archiveRows.first()).toBeVisible();
  });

  test('detail drawer, integrity check and CSV export', async ({ page, request }) => {
    test.setTimeout(120_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    const category = await createCategory(request, token, `E2E აუდიტის კატეგორია ${id}`);
    const articleId = await createArticle(request, token, {
      title: `E2E აუდიტის სტატია ${id}`,
      categoryId: category.id,
      targetDepartments: ['საინფორმაციო']
    });
    await writeAuditRows(request, token, articleId, 2);

    await seedTokenIntoPage(page, token);
    await page.goto('/admin/audit');

    const row = page.locator('tr', { hasText: 'ARCHIVE' }).first();
    await expect(row).toBeVisible();

    // --- open by clicking the row, close with the X ------------------------
    await row.click();
    await expect(page.getByText('ლოგის დეტალები')).toBeVisible();
    await page.locator('button:has(.fa-xmark)').click();
    await expect(page.getByText('ლოგის დეტალები')).toBeHidden();

    // --- the shield icon opens the drawer AND verifies in one click --------
    // It has to stopPropagation, or the row's own handler would fight it.
    const [verifyResponse] = await Promise.all([
      page.waitForResponse((r) => /\/api\/audit-logs\/\d+\/verify/.test(r.url())),
      row.locator('button:has(.fa-link)').click()
    ]);
    expect(verifyResponse.status(), 'the integrity check must reach the server').toBe(200);
    await expect(page.getByText('ჯაჭვი დამოწმებულია')).toBeVisible();

    // The button inside the drawer re-runs the same check.
    const [again] = await Promise.all([
      page.waitForResponse((r) => /\/api\/audit-logs\/\d+\/verify/.test(r.url())),
      page.getByRole('button', { name: 'ჯაჭვის შემოწმება' }).click()
    ]);
    expect(again.status()).toBe(200);

    // --- close by clicking the backdrop ------------------------------------
    await page.locator('div.fixed.inset-0.bg-black\\/40').click({ position: { x: 5, y: 5 } });
    await expect(page.getByText('ლოგის დეტალები')).toBeHidden();

    // --- export ------------------------------------------------------------
    // Asserted on the request, not on a file landing somewhere: the download
    // is a blob the page builds itself, so the only thing that proves the
    // export worked is the server answering the export endpoint.
    const [exportResponse] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/audit-logs/export')),
      page.getByRole('button', { name: 'ექსპორტი' }).click()
    ]);
    expect(exportResponse.status(), 'the CSV export must reach the server').toBe(200);

    // --- refresh -----------------------------------------------------------
    const [refreshed] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/audit-logs?') || r.url().endsWith('/api/audit-logs')),
      page.locator('button:has(.fa-rotate-right)').click()
    ]);
    expect(refreshed.status()).toBe(200);
  });
});
