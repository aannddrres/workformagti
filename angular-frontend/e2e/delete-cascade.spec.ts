import { test, expect } from '@playwright/test';
import { apiLogin, createArticle, firstCategoryId, markRead, runId, seedTokenIntoPage, syncRequiredReading } from './helpers';

/**
 * The migration plan originally assumed ArticleController's delete
 * endpoint 409s when a read receipt already exists for the article. Live-
 * probing the isolated Java backend (curl, outside this suite) showed that
 * assumption is stale: the current code has no conflict check at all --
 * DELETE just cascades through required_readings/read_status/etc and
 * returns 204, even with an existing read receipt in place.
 *
 * That cascade path is exactly what the earlier "DELETE /api/articles/{id}
 * 500" bug fix (V30 migration, ON DELETE CASCADE on user_notes/
 * knowledge_feedback) was meant to make safe. So instead of a fictional
 * 409, this test verifies the actually-meaningful regression: deleting an
 * article that already has a required-reading assignment AND a read
 * receipt against it does not 500, succeeds cleanly, and the row leaves
 * the admin table.
 */
test('deleting an article with an existing read receipt cascades cleanly (no 500 regression)', async ({
  page,
  request
}) => {
  const id = runId();
  const adminToken = await apiLogin(request, 'admin@magti.ge');
  const categoryId = await firstCategoryId(request, adminToken);

  const title = `E2E delete-cascade ${id}`;
  const articleId = await createArticle(request, adminToken, {
    title,
    categoryId,
    targetDepartments: ['Support']
  });

  const dueDate = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString();
  const readingId = await syncRequiredReading(request, adminToken, articleId, 'Support', dueDate);

  const operatorToken = await apiLogin(request, `test_operator_delcascade_${id}@magti.ge`);
  await markRead(request, operatorToken, readingId);

  // R5 made removal a two-step, evidence-safe path: an article has to be
  // archived before it can go to the 30-day trash, and the menu item stays
  // disabled until it is. Archived through the API rather than the row menu
  // -- this spec is about what the cascade does on delete, and driving the
  // dropdown twice only adds a way for it to fail for an unrelated reason.
  const archived = await request.post(`/api/articles/${articleId}/archive`, {
    headers: { Authorization: `Bearer ${adminToken}` }
  });
  expect(archived.ok(), `archive failed: ${archived.status()}`).toBeTruthy();

  await seedTokenIntoPage(page, adminToken);
  await page.goto('/admin/content');

  await page.getByPlaceholder('ძიება სათაურით...').fill(title);
  const row = page.locator('tr', { hasText: title });
  await expect(row).toBeVisible();

  // The endpoint and its status are unchanged -- DELETE /api/articles/{id}
  // still answers 204 -- so the thing this spec exists for, that the cascade
  // does not 500 on an article somebody has already read, is asserted exactly
  // as before.
  await row.getByRole('button').click(); // ellipsis menu toggle
  page.once('dialog', (dialog) => dialog.accept());

  const [deleteResponse] = await Promise.all([
    page.waitForResponse((res) => res.url().includes(`/api/articles/${articleId}`) && res.request().method() === 'DELETE'),
    row.getByRole('button', { name: 'სანაგვეში გადატანა' }).click()
  ]);

  expect(deleteResponse.status(), 'delete must not regress to a 500').toBe(204);
  await expect(row).not.toBeVisible();
});
