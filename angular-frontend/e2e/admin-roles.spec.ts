import { test, expect, APIRequestContext } from '@playwright/test';
import { apiLogin, createArticle, createCategory, runId, seedTokenIntoPage } from './helpers';

/**
 * The role-management screen, which is the one place in the product where a
 * single click changes what several people are allowed to do.
 *
 * That is the reason every assertion here reads the result back from the
 * server rather than from the table: a bulk move that repainted the UI and
 * changed nothing would look identical, and the screen it looks identical on
 * is the one that grants and removes admin.
 */
async function createOperator(
  request: APIRequestContext,
  token: string,
  email: string,
  name: string
): Promise<number> {
  const res = await request.post('/api/users', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      email,
      name,
      department: 'ტექნიკური',
      position: null,
      phone: null,
      role: 'operator',
      password: 'E2Epass123!',
      team_id: null
    }
  });
  expect(res.ok(), `seed user failed: ${res.status()} ${await res.text()}`).toBeTruthy();
  return (await res.json()).id as number;
}

async function roleOf(request: APIRequestContext, token: string, userId: number): Promise<string> {
  const res = await request.get('/api/users', { headers: { Authorization: `Bearer ${token}` } });
  expect(res.ok()).toBeTruthy();
  const user = (await res.json()).find((u: { id: number }) => u.id === userId);
  expect(user, `user ${userId} vanished from /api/users`).toBeTruthy();
  return user.role as string;
}

test.describe('role management', () => {
  test('bulk move: select two operators, move them to manager, read it back', async ({
    page,
    request
  }) => {
    test.setTimeout(180_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');

    const moveName = `E2E გადასაყვანი ${id}`;
    const stayName = `E2E დარჩენილი ${id}`;
    const moveId = await createOperator(request, token, `e2e_role_move_${id}@magti.ge`, moveName);
    const stayId = await createOperator(request, token, `e2e_role_stay_${id}@magti.ge`, stayName);

    await seedTokenIntoPage(page, token);
    await page.goto('/admin/roles');
    await expect(page.getByRole('heading', { name: 'როლების მართვა' })).toBeVisible();

    // --- refresh -----------------------------------------------------------
    const [refreshed] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/users')),
      page.getByRole('button', { name: 'განახლება' }).click()
    ]);
    expect(refreshed.status()).toBe(200);

    // --- pick the operator card --------------------------------------------
    // Selecting a role is what populates the member table below it; before
    // that there is nothing to tick.
    await page.getByRole('button', { name: /ოპერატორი/ }).first().click();

    const moveRow = page.locator('tbody tr', { hasText: moveName });
    const stayRow = page.locator('tbody tr', { hasText: stayName });
    await expect(moveRow).toHaveCount(1);
    await expect(stayRow).toHaveCount(1);

    // --- select all, then narrow to one ------------------------------------
    // Ticking every operator and then clearing all but one is a deliberate
    // route to a single selection: it exercises toggleAll AND toggleMember,
    // and it proves the bulk move honours the selection rather than moving
    // whatever role happens to be open. The second operator staying an
    // operator is the assertion that carries that.
    const selectAll = page.getByRole('checkbox').first();
    await selectAll.check();
    await expect(page.getByText(/\d+ მონიშნული/)).toBeVisible();
    await expect(moveRow.getByRole('checkbox')).toBeChecked();
    await expect(stayRow.getByRole('checkbox')).toBeChecked();

    await selectAll.uncheck();
    await expect(moveRow.getByRole('checkbox')).not.toBeChecked();

    await moveRow.getByRole('checkbox').check();
    await expect(page.getByText('1 მონიშნული')).toBeVisible();

    // --- choose the target role and move -----------------------------------
    // The select carries the four roles in ROLE_ORDER
    // (admin-roles-page.ts:8); manager is the safe one to move into for a
    // fixture -- it grants no ability to edit users back.
    await page.locator('select').selectOption('manager');

    // A native window.confirm() gates the move (admin-roles-page.ts:158) and
    // Playwright dismisses dialogs by default, so without this the request is
    // never made at all. Accepting it here rather than routing around it: the
    // confirmation IS the feature -- an explicit step before a change that
    // rewrites what several people are allowed to do.
    page.once('dialog', (dialog) => dialog.accept());

    const [moved] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/admin/roles/bulk-reassign')),
      page.getByRole('button', { name: 'გადაყვანა', exact: true }).click()
    ]);
    expect(moved.status(), 'the bulk move must reach the server').toBe(200);

    // --- the only assertion that matters -----------------------------------
    await expect
      .poll(() => roleOf(request, token, moveId), {
        message: 'the selected operator was never actually moved'
      })
      .toBe('manager');
    expect(
      await roleOf(request, token, stayId),
      'an operator that was NOT selected got moved too'
    ).toBe('operator');
  });

  test('the edit button opens the modal on the row it sits on', async ({ page, request }) => {
    test.setTimeout(180_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');

    // Two members, because the failure worth catching is a row-scoped button
    // that ignores its row -- and that is invisible with one.
    const targetName = `E2E სარედაქციო ${id}`;
    const otherName = `E2E სხვა წევრი ${id}`;
    const targetId = await createOperator(request, token, `e2e_edit_a_${id}@magti.ge`, targetName);
    const otherId = await createOperator(request, token, `e2e_edit_b_${id}@magti.ge`, otherName);

    await seedTokenIntoPage(page, token);
    await page.goto('/admin/roles');
    await page.getByRole('button', { name: /ოპერატორი/ }).first().click();

    const row = page.locator('tbody tr', { hasText: targetName });
    await expect(row).toHaveCount(1);
    await row.getByRole('button', { name: 'რედაქტირება' }).click();

    const modal = page.locator('app-user-edit-modal');
    await expect(modal.getByText('მომხმარებლის რედაქტირება')).toBeVisible();

    // The modal shows role, department and permissions -- nothing that names
    // the member. So WHICH member it opened on is proved by saving a change
    // and reading back who it landed on, which is also the only thing that
    // would matter if it were wrong.
    await modal.locator('select').first().selectOption('manager');
    const [saved] = await Promise.all([
      page.waitForResponse((r) => /\/api\/users\/\d+/.test(r.url()) && r.request().method() !== 'GET'),
      modal.getByRole('button', { name: 'შენახვა' }).click()
    ]);
    expect(saved.status(), 'the save must reach the server').toBe(200);

    await expect
      .poll(() => roleOf(request, token, targetId), {
        message: 'the edit modal saved onto nobody'
      })
      .toBe('manager');
    expect(
      await roleOf(request, token, otherId),
      'the edit modal saved onto the wrong member'
    ).toBe('operator');
  });
});

test.describe('user administration', () => {
  test('the group filter recovers when its list fails to load', async ({ page, request }) => {
    test.setTimeout(120_000);
    const token = await apiLogin(request, 'admin@magti.ge');
    await seedTokenIntoPage(page, token);

    // The retry is only reachable through a failure, same as the reading
    // confirm panel. Worth covering: with no group list the filter silently
    // offers nothing, which looks exactly like an organisation that has no
    // groups.
    await page.route('**/api/admin/group-leaders', (route) => route.abort());
    await page.goto('/admin/users');
    await expect(page.getByRole('heading', { name: 'მომხმარებლების მართვა (RBAC)' })).toBeVisible();

    const retry = page.getByRole('button', { name: /ჯგუფების სია ვერ ჩაიტვირთა/ });
    await expect(retry).toBeVisible();

    await page.unroute('**/api/admin/group-leaders');
    const [reloaded] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/admin/group-leaders')),
      retry.click()
    ]);
    expect(reloaded.status()).toBe(200);
    await expect(retry).toHaveCount(0);

    // --- and the filter itself ---------------------------------------------
    const groupSelect = page.locator('select').filter({ hasText: 'ყველა ჯგუფი' });
    await expect(groupSelect).toBeVisible();

    const rows = page.locator('tbody tr');
    const before = await rows.count();
    expect(before, 'no users to filter').toBeGreaterThan(0);

    const options = await groupSelect.locator('option').count();
    if (options > 1) {
      await groupSelect.selectOption({ index: 1 });
      // A filter never adds rows. Asserted as a subset rather than an exact
      // count because the user list is whatever the instance holds.
      await expect.poll(() => rows.count()).toBeLessThanOrEqual(before);
      await groupSelect.selectOption('');
      await expect.poll(() => rows.count()).toBe(before);
    }
  });

  test('the dashboard opens an article from the recently-viewed strip', async ({ page, request }) => {
    test.setTimeout(120_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    const category = await createCategory(request, token, `E2E ბოლოს ნანახი კატეგორია ${id}`);
    const title = `E2E ბოლოს ნანახი ${id}`;
    const articleId = await createArticle(request, token, {
      title,
      categoryId: category.id,
      targetDepartments: ['საინფორმაციო']
    });

    await seedTokenIntoPage(page, token);

    // The strip is built from view history, so the article has to be OPENED
    // first -- there is no other way to put something in it.
    await page.goto(`/article/${articleId}`);
    await expect(page.getByRole('heading', { name: title })).toBeVisible();

    await page.goto('/');
    const strip = page.locator('app-recently-viewed-strip');
    const entry = strip.locator('div[role="button"], button').filter({ hasText: title });
    await expect(entry.first()).toBeVisible();

    await entry.first().click();
    await expect(page).toHaveURL(new RegExp(`/article/${articleId}$`));
  });
});
