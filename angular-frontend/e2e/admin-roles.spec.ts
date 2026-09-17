import { test, expect, APIRequestContext } from '@playwright/test';
import { acceptConfirmation, apiLogin, createArticle, createCategory, runId, seedTokenIntoPage } from './helpers';

interface UserRow { id: number; email: string; name: string; role: string }

async function users(request: APIRequestContext, token: string): Promise<UserRow[]> {
  const response = await request.get('/api/users', { headers: { Authorization: `Bearer ${token}` } });
  expect(response.ok()).toBeTruthy();
  return response.json();
}

async function roleOf(request: APIRequestContext, token: string, userId: number): Promise<string> {
  return (await users(request, token)).find((user) => user.id === userId)!.role;
}

test.describe('role management', () => {
  test('bulk reassignment changes only the selected directory user', async ({ page, request }) => {
    test.setTimeout(120_000);
    const token = await apiLogin(request, 'admin@magti.ge');
    // tech@magti.ge is JIT-provisioned by its FIRST login and is not one of
    // the SHARED_PERSONAS global-setup signs in, so against a fresh database
    // it does not exist yet -- the directory lookup below came back undefined
    // and the test died on "cannot read properties of undefined". Only
    // department-visibility.spec.ts signed it in, and that runs later.
    // info@magti.ge is a shared persona and is already there.
    await apiLogin(request, 'tech@magti.ge');
    const target = (await users(request, token)).find((user) => user.email === 'tech@magti.ge')!;
    const untouched = (await users(request, token)).find((user) => user.email === 'info@magti.ge')!;
    expect(target, 'tech@magti.ge was not provisioned by its first login').toBeTruthy();
    expect(target.role).toBe('operator');
    expect(untouched.role).toBe('operator');

    try {
      await seedTokenIntoPage(page, token);
      await page.goto('/admin/access');
      await page.getByRole('tab', { name: 'როლები და უფლებები' }).click();
      await expect(page.getByRole('heading', { name: 'როლების მართვა' })).toBeVisible();

      const row = page.locator('tbody tr', { hasText: target.email });
      await expect(row).toHaveCount(1);
      await row.getByRole('checkbox').check();
      await expect(page.getByText('1 მონიშნული')).toBeVisible();
      await page.locator('app-admin-roles-page select').selectOption('manager');
      const [moved] = await Promise.all([
        page.waitForResponse((response) => response.url().includes('/api/admin/roles/bulk-reassign')),
        page.getByRole('button', { name: 'გადაყვანა', exact: true }).click().then(() => acceptConfirmation(page))
      ]);
      expect(moved.status()).toBe(200);
      await expect.poll(() => roleOf(request, token, target.id)).toBe('manager');
      expect(await roleOf(request, token, untouched.id)).toBe('operator');
    } finally {
      if (await roleOf(request, token, target.id) !== 'operator') {
        const restored = await request.post('/api/admin/roles/bulk-reassign', {
          headers: { Authorization: `Bearer ${token}` },
          data: { user_ids: [target.id], new_role: 'operator' }
        });
        expect(restored.ok(), 'test user role must be restored').toBeTruthy();
      }
    }
  });

  test('row edit opens the selected AD identity and keeps identity fields read-only', async ({ page, request }) => {
    const token = await apiLogin(request, 'admin@magti.ge');
    const target = (await users(request, token)).find((user) => user.email === 'info@magti.ge')!;

    await seedTokenIntoPage(page, token);
    await page.goto('/admin/access');
    await page.getByRole('tab', { name: 'როლები და უფლებები' }).click();
    const row = page.locator('tbody tr', { hasText: target.email });
    await row.getByRole('button', { name: 'რედაქტირება' }).click();

    const modal = page.locator('app-user-edit-modal');
    await expect(modal.getByText(target.name, { exact: true })).toBeVisible();
    await expect(modal.getByText(target.email, { exact: true })).toBeVisible();
    await expect(modal.getByText('იმართება კომპანიის AD-დან')).toHaveCount(2);
    await expect(modal.locator('input[type="email"], input[type="password"]')).toHaveCount(0);
    await modal.getByRole('button', { name: 'გაუქმება' }).click();
    await expect(modal).toHaveCount(0);
  });
});

test.describe('user administration', () => {
  test('the group filter recovers when its directory list fails to load', async ({ page, request }) => {
    const token = await apiLogin(request, 'admin@magti.ge');
    await seedTokenIntoPage(page, token);
    await page.route('**/api/admin/group-leaders', (route) => route.abort());
    await page.goto('/admin/access');
    await expect(page.getByRole('heading', { name: 'მომხმარებლები და წვდომა' })).toBeVisible();

    const retry = page.getByRole('button', { name: /ჯგუფების სია ვერ ჩაიტვირთა/ });
    await expect(retry).toBeVisible();
    await page.unroute('**/api/admin/group-leaders');
    const [reloaded] = await Promise.all([
      page.waitForResponse((response) => response.url().includes('/api/admin/group-leaders')),
      retry.click()
    ]);
    expect(reloaded.status()).toBe(200);
    await expect(retry).toHaveCount(0);

    const groupSelect = page.locator('select').filter({ hasText: 'ყველა ჯგუფი' });
    await expect(groupSelect).toBeVisible();
    const before = await page.locator('tbody tr').count();
    if (await groupSelect.locator('option').count() > 1) {
      await groupSelect.selectOption({ index: 1 });
      await expect.poll(() => page.locator('tbody tr').count()).toBeLessThanOrEqual(before);
    }
  });

  test('the dashboard opens an article from the recently-viewed strip', async ({ page, request }) => {
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    const category = await createCategory(request, token, `E2E ბოლოს ნანახი კატეგორია ${id}`);
    const title = `E2E ბოლოს ნანახი ${id}`;
    const articleId = await createArticle(request, token, {
      title, categoryId: category.id, targetDepartments: ['საინფორმაციო']
    });
    const operatorToken = await apiLogin(request, 'info@magti.ge');

    await seedTokenIntoPage(page, operatorToken);
    // The view is recorded by a fire-and-forget POST once the article loads,
    // and this test is about that record. Waiting for its answer keeps the
    // next page.goto from racing it, and asserting the answer names the real
    // failure when it is lost: on 2026-09-17 the article ended up with no view
    // row at all and the spec reported only a missing strip entry. The loss
    // itself is the backend replacing the XSRF cookie on every signed-in
    // response, which can leave a POST's header behind the cookie it is sent
    // with -- rejected as 401. That is a product defect, not this test's.
    const viewLogged = page.waitForResponse(
      (r) => r.url().endsWith(`/api/articles/${articleId}/view`) && r.request().method() === 'POST'
    );
    await page.goto(`/article/${articleId}`);
    await expect(page.getByRole('heading', { name: title })).toBeVisible();
    expect((await viewLogged).ok()).toBeTruthy();
    await page.goto('/');
    const entry = page.locator('app-recently-viewed-strip').getByRole('button', { name: new RegExp(title) });
    await expect(entry).toBeVisible();
    await entry.click();
    await expect(page).toHaveURL(new RegExp(`/article/${articleId}$`));
  });
});
