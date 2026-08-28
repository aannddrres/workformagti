import { test, expect } from '@playwright/test';
import { apiLogin, createArticle, createCategory, runId, seedTokenIntoPage } from './helpers';

const SEEDED = 21;

test.describe('unified admin content queue', () => {
  test('type tabs, search, URL state and paging stay unified', async ({ page, request }) => {
    test.setTimeout(120_000);
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

    const search = page.getByLabel('ძიება');
    await expect(search).toBeVisible();

    // Q115: all content types share one queue and one filter language.
    await page.getByRole('tab', { name: 'სიახლეები', exact: true }).click();
    await expect(page).toHaveURL(/type=news/);
    await expect(search).toBeVisible();
    await page.getByRole('tab', { name: 'ვიდეოები', exact: true }).click();
    await expect(page).toHaveURL(/type=video/);
    await page.getByRole('tab', { name: 'სტატიები', exact: true }).click();
    await expect(page).toHaveURL(/type=article/);

    await search.fill(marker);
    await expect.poll(() => new URL(page.url()).searchParams.get('q')).toBe(marker);
    await expect(page.getByText(`1–20 / ${SEEDED}`)).toBeVisible();
    await expect(page.locator('tbody tr')).toHaveCount(20);

    const next = page.getByRole('button', { name: 'შემდეგი გვერდი' });
    const previous = page.getByRole('button', { name: 'წინა გვერდი' });
    await expect(previous).toBeDisabled();
    await next.click();
    await expect(page.getByText(`21–${SEEDED} / ${SEEDED}`)).toBeVisible();
    await expect(page.locator('tbody tr')).toHaveCount(1);
    await expect(page).toHaveURL(/page=2/);
    await previous.click();
    await expect(page.getByText(`1–20 / ${SEEDED}`)).toBeVisible();

    await search.fill(`${marker}-არარსებული`);
    await expect(page.getByText('შესაბამისი კონტენტი ვერ მოიძებნა')).toBeVisible();
  });

  test('status filter and article lifecycle actions share the queue language', async ({ page, request }) => {
    test.setTimeout(120_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    const category = await createCategory(request, token, `E2E ფილტრის კატეგორია ${id}`);
    const title = `E2Eფილტრი${id}`;
    const articleId = await createArticle(request, token, {
      title,
      categoryId: category.id,
      targetDepartments: ['საინფორმაციო']
    });

    await seedTokenIntoPage(page, token);
    await page.goto('/admin/content?type=article');
    await page.getByLabel('ძიება').fill(title);

    const row = page.locator('tbody tr', { hasText: title });
    await expect(row).toHaveCount(1);
    await row.getByRole('button', { name: 'სტატიის მოქმედებები' }).click();
    await expect(row.getByRole('button', { name: 'ისტორია' })).toBeVisible();
    await expect(row.getByRole('button', { name: 'სანაგვეში გადატანა' })).toBeDisabled();

    page.once('dialog', (dialog) => dialog.accept());
    const [archived] = await Promise.all([
      page.waitForResponse((response) => response.url().includes(`/api/articles/${articleId}/archive`)),
      row.getByRole('button', { name: 'დაარქივება' }).click()
    ]);
    expect(archived.status()).toBe(200);
    await expect(row.getByText('არქივი')).toBeVisible();

    const status = page.getByLabel('სტატუსი');
    await status.selectOption('published');
    await expect(row).toHaveCount(0);
    await status.selectOption('archived');
    await expect(row).toHaveCount(1);
    await expect(page).toHaveURL(/status=archived/);

    await row.getByRole('button', { name: 'სტატიის მოქმედებები' }).click();
    page.once('dialog', (dialog) => dialog.accept());
    const [restored] = await Promise.all([
      page.waitForResponse((response) => response.url().includes(`/api/articles/${articleId}/unarchive`)),
      row.getByRole('button', { name: 'არქივიდან ამოღება' }).click()
    ]);
    expect(restored.status()).toBe(200);
    await expect(row).toHaveCount(0);
  });
});
