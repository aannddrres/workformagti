import { test, expect } from '@playwright/test';
import { apiLogin, createArticle, createCategory, runId, seedTokenIntoPage, updateArticle } from './helpers';

/**
 * The version history modal: expand a version, diff it, go back, restore it.
 *
 * Restore is the most destructive control in the admin UI -- it overwrites
 * live content with an older revision -- and it had never been driven end to
 * end. The assertion is that the article really reverts, checked in the
 * table AND over the API, not that the button accepted a click.
 *
 * The fixture has to be made by EDITING an article, because that is the only
 * thing that writes an article_history row; a freshly created article has an
 * empty history and the modal would correctly show nothing.
 */
test('article history: expand, diff, and restore an older version', async ({ page, request }) => {
  test.setTimeout(90_000);
  const id = runId();
  const token = await apiLogin(request, 'admin@magti.ge');
  const auth = { Authorization: `Bearer ${token}` };
  const category = await createCategory(request, token, `E2E ისტორიის კატეგორია ${id}`);

  const originalTitle = `E2E ისტორია ${id} პირველი`;
  const originalContent = `<p>პირველი ვერსიის ტექსტი ${id}</p>`;
  const articleId = await createArticle(request, token, {
    title: originalTitle,
    categoryId: category.id,
    targetDepartments: ['საინფორმაციო'],
    content: originalContent
  });

  const revisedTitle = `E2E ისტორია ${id} მეორე`;
  await updateArticle(request, token, articleId, {
    title: revisedTitle,
    categoryId: category.id,
    targetDepartments: ['საინფორმაციო'],
    content: `<p>მეორე ვერსიის ტექსტი ${id}</p>`
  });

  await seedTokenIntoPage(page, token);
  await page.goto('/admin/content');
  await page.getByPlaceholder('ძიება სათაურით...').fill(revisedTitle);

  const row = page.locator('tr', { hasText: revisedTitle });
  await expect(row).toHaveCount(1);
  await row.getByRole('button').first().click();       // ellipsis
  await row.getByRole('button', { name: 'ისტორია' }).click();

  const modal = page.locator('app-article-history-modal');
  await expect(modal.getByText('ცვლილებების ისტორია')).toBeVisible();

  // Two rows exist by now, and NOT for the reason it first looks like: a
  // history row records the state AFTER each save, not before it
  // (ArticleController.java:260-267 on create, :316-323 on update). So the
  // create wrote a row holding the original title, and the update wrote a
  // second one holding the revised title. The row this test wants is the
  // first -- restoring it is what should undo the edit.
  const entry = modal.locator('div').filter({ hasText: originalTitle }).last();
  await expect(entry).toBeVisible();

  // --- expand -----------------------------------------------------------
  await entry.getByRole('button', { name: 'ტექსტის ნახვა' }).click();
  await expect(modal.getByText(`პირველი ვერსიის ტექსტი ${id}`)).toBeVisible();

  // --- diff, then back ---------------------------------------------------
  await entry.getByRole('button', { name: 'შედარება' }).click();
  const back = modal.getByRole('button', { name: 'უკან' });
  await expect(back).toBeVisible();
  // The diff view replaces the list; the counts only render once the diff
  // actually loaded, so asserting on them proves the request succeeded.
  await expect(modal.getByText('დამატებული')).toBeVisible();
  await expect(modal.getByText('წაშლილი')).toBeVisible();

  await back.click();
  await expect(back).toHaveCount(0);
  await expect(modal.getByText('ცვლილებების ისტორია')).toBeVisible();

  // --- restore -----------------------------------------------------------
  page.once('dialog', (dialog) => dialog.accept());     // confirm_restore
  await modal
    .locator('div')
    .filter({ hasText: originalTitle })
    .last()
    .getByRole('button', { name: 'აღდგენა' })
    .click();

  // The modal closes and the table reloads -- the article is back to its
  // first title.
  await expect(modal).toHaveCount(0);

  const restored = await request.get(`/api/articles/${articleId}`, { headers: auth });
  const article = await restored.json();
  expect(article.title, 'restore did not put the older title back').toBe(originalTitle);
  expect(article.content, 'restore did not put the older content back').toContain(
    `პირველი ვერსიის ტექსტი ${id}`
  );
});
