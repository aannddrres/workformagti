import { test, expect } from '@playwright/test';
import { apiLogin, createArticle, createCategory, runId, seedTokenIntoPage } from './helpers';

/**
 * The admin content table's own controls: tabs, the three filters, the
 * per-row menu, selection and the pager.
 *
 * These had all been *clicked* by the click sweep -- nothing threw, no
 * request failed -- but nothing checked that pressing them changed anything.
 * "The button does not crash" and "the button filters the list" are
 * different claims, and only the second one is worth having. Every
 * assertion here is on the resulting list, not on the control.
 */

/** PAGE_SIZE is 20 (admin-content-page.ts:15), so 21 articles under one
 *  unique marker gives the pager exactly two pages to move between. */
const SEEDED = 21;

test.describe('admin content list', () => {
  test('tabs, search and the pager', async ({ page, request }) => {
    test.setTimeout(90_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    const category = await createCategory(request, token, `E2E სიის კატეგორია ${id}`);

    const marker = `E2Eსია${id}`;
    for (let i = 1; i <= SEEDED; i++) {
      await createArticle(request, token, {
        title: `${marker} ${String(i).padStart(2, '0')}`,
        categoryId: category.id,
        targetDepartments: ['საინფორმაციო']
      });
    }

    await seedTokenIntoPage(page, token);
    await page.goto('/admin/content');

    // --- tabs -------------------------------------------------------------
    // Each tab owns a different table, so the assertion is which table is on
    // screen, not which button looks active.
    const search = page.getByPlaceholder('ძიება სათაურით...');
    await expect(search).toBeVisible();

    await page.getByRole('button', { name: 'სიახლეები', exact: true }).click();
    await expect(search).toBeHidden();
    await page.getByRole('button', { name: 'ვიდეოები', exact: true }).click();
    await expect(search).toBeHidden();
    await page.getByRole('button', { name: 'სტატიები', exact: true }).click();
    await expect(search).toBeVisible();

    // --- search + pager ---------------------------------------------------
    // filteredArticles() is computed client-side, so the marker narrows the
    // list to exactly the 21 rows this test seeded regardless of what else
    // the database holds.
    await search.fill(marker);
    await expect(page.getByText(`1–20 / ${SEEDED}`)).toBeVisible();
    await expect(page.locator('tbody tr')).toHaveCount(20);

    // The pager buttons are icon-only, so they are addressed by their icon
    // rather than by position -- an index would silently follow whichever
    // button moved.
    const firstPage = page.locator('button:has(.fa-angles-left)');
    const prevPage = page.locator('button:has(.fa-chevron-left)');
    const nextPage = page.locator('button:has(.fa-chevron-right)');
    const lastPage = page.locator('button:has(.fa-angles-right)');

    await page.getByRole('button', { name: '2', exact: true }).click();
    await expect(page.getByText(`21–${SEEDED} / ${SEEDED}`)).toBeVisible();
    await expect(page.locator('tbody tr')).toHaveCount(1);

    await firstPage.click();
    await expect(page.getByText(`1–20 / ${SEEDED}`)).toBeVisible();
    await nextPage.click();
    await expect(page.getByText(`21–${SEEDED} / ${SEEDED}`)).toBeVisible();
    await prevPage.click();
    await expect(page.getByText(`1–20 / ${SEEDED}`)).toBeVisible();
    await lastPage.click();
    await expect(page.getByText(`21–${SEEDED} / ${SEEDED}`)).toBeVisible();

    // A search that matches nothing must say so, not silently show page 1.
    await search.fill(`${marker}-არარსებული`);
    await expect(page.getByText('სტატიები არ მოიძებნა')).toBeVisible();
  });

  test('category filter, status filter, row menu and bulk archive', async ({ page, request }) => {
    test.setTimeout(90_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');

    // Two categories so filtering by one can be shown to EXCLUDE the other.
    // A filter that keeps everything visible proves nothing.
    const kept = await createCategory(request, token, `E2E დარჩენილი ${id}`);
    const excluded = await createCategory(request, token, `E2E გაფილტრული ${id}`);

    const marker = `E2Eფილტრი${id}`;
    const keptTitle = `${marker} დარჩენილი`;
    const excludedTitle = `${marker} გაფილტრული`;
    await createArticle(request, token, {
      title: keptTitle, categoryId: kept.id, targetDepartments: ['საინფორმაციო']
    });
    await createArticle(request, token, {
      title: excludedTitle, categoryId: excluded.id, targetDepartments: ['საინფორმაციო']
    });

    await seedTokenIntoPage(page, token);
    await page.goto('/admin/content');

    const search = page.getByPlaceholder('ძიება სათაურით...');
    await search.fill(marker);

    // Assert on ROWS, not on the title text: the title cell holds the title
    // and its status badge in the same element, so getByText(title) can
    // resolve to more than one node and a strict-mode error would read as a
    // filter failure.
    const keptRow = page.locator('tr', { hasText: keptTitle });
    const excludedRow = page.locator('tr', { hasText: excludedTitle });
    await expect(page.locator('tbody tr')).toHaveCount(2);

    // --- category filter --------------------------------------------------
    const categorySelect = page.locator('select').first();
    await categorySelect.selectOption({ label: kept.name });
    await expect(keptRow).toHaveCount(1);
    await expect(excludedRow).toHaveCount(0);
    await categorySelect.selectOption('');
    await expect(page.locator('tbody tr')).toHaveCount(2);

    // --- row menu: archive one --------------------------------------------
    await keptRow.getByRole('button').click();                   // ellipsis
    await expect(keptRow.getByRole('button', { name: 'ისტორია' })).toBeVisible();
    page.once('dialog', (dialog) => dialog.accept());            // confirm_archive_one
    await keptRow.getByRole('button', { name: 'დაარქივება' }).click();

    // The row does not disappear -- it comes back with the archived badge,
    // which is the whole point of archive-instead-of-delete.
    await expect(keptRow.getByText('არქივი')).toBeVisible();

    // --- status filter ----------------------------------------------------
    const statusSelect = page.locator('select').nth(1);
    await statusSelect.selectOption('archived');
    await expect(keptRow).toHaveCount(1);
    await expect(excludedRow).toHaveCount(0);
    await statusSelect.selectOption('published');
    await expect(excludedRow).toHaveCount(1);
    await expect(keptRow).toHaveCount(0);
    await statusSelect.selectOption('');

    // --- select-all + bulk archive ---------------------------------------
    // The bulk buttons are disabled with an empty selection, so the enabled
    // state and the count in the label are themselves the assertion that
    // select-all reached the component.
    const archiveSelected = page.getByRole('button', { name: /არქივი \(/ });
    const selectAll = page.locator('thead input[type="checkbox"]');
    await selectAll.check();
    await expect(archiveSelected).toBeEnabled();
    await expect(archiveSelected).toContainText('(2)');

    page.once('dialog', (dialog) => dialog.accept());            // confirm_bulk_archive
    await archiveSelected.click();
    await expect(excludedRow.getByText('არქივი')).toBeVisible();

    // --- and back out of the archive, one row at a time -------------------
    await excludedRow.getByRole('button').first().click();
    page.once('dialog', (dialog) => dialog.accept());            // confirm_unarchive_one
    await excludedRow.getByRole('button', { name: 'ამოღება არქივიდან' }).click();
    await expect(excludedRow.getByText('გამოქვეყნებული')).toBeVisible();
  });
});
