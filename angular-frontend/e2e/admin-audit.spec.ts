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
    // 180s, not 120s. The fixture below writes PAGE_SIZE + 2 audit rows one
    // at a time, because each row hash-chains onto the previous one and there
    // is no bulk path -- 52 sequential round-trips against a real Oracle
    // before the first assertion runs. That is genuinely most of the budget
    // on a slow runner, and this test has now failed on the clock twice while
    // passing on faster ones. Sizing the budget to the work, not hiding a
    // hang: playwright.config.ts caps every individual action at 15s, so a
    // control that never becomes usable still fails fast and says so.
    test.setTimeout(180_000);
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

    // Wait for the filtered REQUEST, not for what the table looks like.
    //
    // Typing debounces 300ms and then calls reload(), which sets offset back
    // to 0 (admin-audit-page.ts:154-157). "წინა" is bound to `offset() <= 0`
    // and nothing else (admin-audit-page.html:196), so a debounce that fires
    // after the pager has moved silently disables it again.
    //
    // The previous version guarded against this by waiting until every row on
    // the page was an ARCHIVE row, on the reasoning that the unfiltered list
    // is a mix. That is true on average and not always: the fixture's 52 rows
    // are the newest ones, so if the VIEW_AUDIT_LOG row for this very page
    // load has not landed yet, the UNFILTERED first page is also 50 ARCHIVE
    // rows and the guard passes without the filter having been applied at all.
    // The debounce then fired between "წინა" being asserted enabled and being
    // clicked. Same commit passed one run and failed the next on exactly that.
    //
    // Waiting on the response removes the race rather than narrowing it: when
    // it arrives, the debounce has fired, the request went out, and there is
    // no second reload pending.
    // action=ARCHIVE, not q=action:ARCHIVE. `action:` is one of three
    // structured tokens the box understands (audit-format.ts:34-50) -- it is
    // parsed out and sent as its own parameter, and only what is left over
    // becomes the free-text `q`. Matching on `q` waited 15s for a request
    // that was never going to be made.
    const [filtered] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/api/audit-logs?') && r.url().includes('action=ARCHIVE')
      ),
      search.fill('action:ARCHIVE')
    ]);
    expect(filtered.status(), 'the filtered query must reach the server').toBe(200);

    const firstPage = page.locator('tbody tr');
    await expect(firstPage).toHaveCount(PAGE_SIZE);
    await expect(archiveRows).toHaveCount(PAGE_SIZE);

    // Asserted on the OFFSET the pager asks the server for, which is the whole
    // of its job (audit.service.ts:6-7), plus the button state that mirrors it.
    //
    // Two earlier versions tried to identify the page by what was in the table.
    // Both were wrong for the same underlying reason: these rows are not
    // distinguishable from each other on screen. Comparing whole row text broke
    // on a cell that re-rendered; comparing the timestamp cell broke because
    // the fixture writes its 52 rows in a tight loop, so many share the same
    // whole second and the table prints seconds -- page 1's top row and page
    // 2's top row can legitimately show the identical string, which is what the
    // last run failed on. Not a pager that refused to move: it had moved.
    const previous = page.getByRole('button', { name: 'წინა' });
    await expect(previous).toBeDisabled();

    const [second] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/audit-logs?') && r.url().includes('offset=50')),
      page.getByRole('button', { name: 'შემდეგი' }).click()
    ]);
    expect(second.status(), 'the pager must fetch the next slice from the server').toBe(200);
    await expect(previous).toBeEnabled();

    const [first] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/audit-logs?') && r.url().includes('offset=0')),
      previous.click()
    ]);
    expect(first.status()).toBe(200);
    // Back at offset 0, and the button says so by disabling itself again.
    await expect(previous).toBeDisabled();

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
