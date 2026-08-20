import { test, expect, APIRequestContext } from '@playwright/test';
import { apiLogin, createArticle, createCategory, runId, seedTokenIntoPage } from './helpers';

/**
 * The parts of the content admin that only appear when something goes wrong,
 * plus the two admin filters whose option lists depend on seeded data.
 *
 * Everything here is a recovery path. They are the controls a content admin
 * meets on their worst day and the ones least likely to have been tried by
 * hand, because reaching them means breaking something first.
 */
async function isArchived(
  request: APIRequestContext,
  token: string,
  articleId: number
): Promise<boolean> {
  const res = await request.get(`/api/articles/${articleId}`, {
    headers: { Authorization: `Bearer ${token}` }
  });
  expect(res.ok(), `article fetch failed: ${res.status()}`).toBeTruthy();
  return (await res.json()).status === 'archived';
}

test.describe('content admin recovery paths', () => {
  test('unarchive a selected article, and dismiss the error when the move fails', async ({
    page,
    request
  }) => {
    test.setTimeout(180_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    const auth = { Authorization: `Bearer ${token}` };
    const category = await createCategory(request, token, `E2E აღდგენის კატეგორია ${id}`);

    const title = `E2E აღსადგენი ${id}`;
    const articleId = await createArticle(request, token, {
      title,
      categoryId: category.id,
      targetDepartments: ['საინფორმაციო']
    });
    // Archived through the API so the UI has something to UNarchive -- the
    // unarchive button is the one of the pair that no spec had reached.
    const archived = await request.post(`/api/articles/${articleId}/archive`, { headers: auth });
    expect(archived.ok(), `archive failed: ${archived.status()}`).toBeTruthy();
    expect(await isArchived(request, token, articleId)).toBe(true);

    await seedTokenIntoPage(page, token);
    await page.goto('/admin/content');
    await expect(page.getByRole('heading', { name: 'კონტენტის მართვა' })).toBeVisible();

    // Narrowed by the page's own search first: this table is paged and shared
    // with every other spec's fixtures, so without it a green result would
    // only mean the article happened to land on the first page.
    await page.getByPlaceholder('ძიება სათაურით...').fill(title);
    const row = page.locator('tbody tr', { hasText: title });
    await expect(row).toHaveCount(1);

    // --- select the row ----------------------------------------------------
    // The count on the button is the visible proof toggleSelected reached the
    // component, and it is what bulkArchive reads.
    await row.getByRole('checkbox').check();
    await expect(page.getByRole('button', { name: /ამოღება \(1\)/ })).toBeVisible();

    // --- the failure path, first -------------------------------------------
    // Driven before the success so the article is still archived for it.
    // dismissActionError has exactly one route: an action that failed.
    await page.route('**/api/articles/bulk-archive', (route) => route.abort());
    page.once('dialog', (dialog) => dialog.accept());
    await page.getByRole('button', { name: /ამოღება/ }).click();

    const banner = page.locator('div.bg-red-50').first();
    await expect(banner).toBeVisible();
    await banner.getByRole('button', { name: 'დახურვა' }).click();
    await expect(banner).toHaveCount(0);

    // The article must still be archived: a failed bulk move that quietly
    // succeeded server-side would be worse than one that reported the error.
    expect(
      await isArchived(request, token, articleId),
      'the aborted request changed the article anyway'
    ).toBe(true);

    // --- and now the success -----------------------------------------------
    await page.unroute('**/api/articles/bulk-archive');
    await expect(row.getByRole('checkbox')).toBeChecked();

    page.once('dialog', (dialog) => dialog.accept());
    const [unarchived] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/articles/bulk-archive')),
      page.getByRole('button', { name: /ამოღება/ }).click()
    ]);
    expect(unarchived.status(), 'the bulk unarchive must reach the server').toBe(200);

    await expect
      .poll(() => isArchived(request, token, articleId), {
        message: 'the article was never actually taken out of the archive'
      })
      .toBe(false);
  });

  test('the category filter says when its list failed, and recovers', async ({ page, request }) => {
    test.setTimeout(120_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    const category = await createCategory(request, token, `E2E ფილტრის კატეგორია ${id}`);

    await seedTokenIntoPage(page, token);

    // The retry is only reachable through a failure. Worth covering: with no
    // category list the filter silently offers only "all", which looks
    // exactly like a portal that has no categories -- and this is the screen
    // where a content admin would go to find out.
    await page.route('**/api/categories', (route) => route.abort());
    await page.goto('/admin/content');
    await expect(page.getByText('კატეგორიების ჩატვირთვა ვერ მოხერხდა.')).toBeVisible();

    await page.unroute('**/api/categories');
    const [reloaded] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/categories')),
      page.getByRole('button', { name: 'თავიდან ცდა' }).click()
    ]);
    expect(reloaded.status()).toBe(200);
    await expect(page.getByText('კატეგორიების ჩატვირთვა ვერ მოხერხდა.')).toHaveCount(0);

    // And the recovered list is usable, not merely present.
    const categorySelect = page.locator('select').filter({ hasText: 'ყველა კატეგორია' });
    await expect(categorySelect.locator('option', { hasText: category.name })).toHaveCount(1);
  });

  test('the row menu opens history and the edit drawer on the article it belongs to', async ({
    page,
    request
  }) => {
    test.setTimeout(120_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    const category = await createCategory(request, token, `E2E მენიუს კატეგორია ${id}`);
    const title = `E2E მენიუს სტატია ${id}`;
    const articleId = await createArticle(request, token, {
      title,
      categoryId: category.id,
      targetDepartments: ['საინფორმაციო']
    });

    await seedTokenIntoPage(page, token);
    await page.goto('/admin/content');
    await page.getByPlaceholder('ძიება სათაურით...').fill(title);

    const row = page.locator('tbody tr', { hasText: title });
    await expect(row).toHaveCount(1);

    // --- history -----------------------------------------------------------
    // Both actions live behind the row's ⋮ menu, which has to be opened
    // first: the buttons do not exist in the DOM until then.
    await row.locator('button:has(.fa-ellipsis-vertical)').click();
    const [versions] = await Promise.all([
      page.waitForResponse((r) => new RegExp(`/api/articles/${articleId}/`).test(r.url())),
      page.getByRole('button', { name: 'ისტორია' }).click()
    ]);
    expect(versions.status(), 'opening history must ask the server about THIS article').toBe(200);

    // The request carrying this article's id is what proves the menu action
    // is scoped to its own row rather than to whatever the component held
    // last -- the same failure the roles screen's edit button could have.
    expect(versions.url()).toContain(`/api/articles/${articleId}/`);

    await page.keyboard.press('Escape');

    // --- edit --------------------------------------------------------------
    await page.getByPlaceholder('ძიება სათაურით...').fill(title);
    await row.locator('button:has(.fa-ellipsis-vertical)').click();
    await page.getByRole('button', { name: 'რედაქტირება' }).click();

    const drawer = page.locator('app-article-edit-drawer');
    await expect(drawer).toBeVisible();
    // The title field carries the article's own title, which is the drawer
    // saying which article it opened on.
    await expect(drawer.locator('input[type="text"]').first()).toHaveValue(title);
  });
});
