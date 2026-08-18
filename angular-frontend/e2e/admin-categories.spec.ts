import { test, expect } from '@playwright/test';
import { apiLogin, runId, seedTokenIntoPage } from './helpers';

/**
 * The category tree: create a parent and a child, expand, edit, then delete
 * the parent and deal with the child it leaves behind.
 *
 * That last part is the reason this is one test rather than three. Deleting
 * a category reassigns its ARTICLES but not its child categories
 * (admin-categories-page.ts:20-25), so removing a parent strands its child
 * as an "orphan" -- and the orphan row has its own edit and delete buttons,
 * which no fixture can produce any other way. Walking the whole lifecycle
 * covers the warning row honestly instead of leaving it as the one branch
 * nothing reaches.
 */
test('categories: create, nest, expand, edit, and the orphan a deleted parent leaves', async ({
  page,
  request
}) => {
  test.setTimeout(90_000);
  const id = runId();
  const token = await apiLogin(request, 'admin@magti.ge');
  const auth = { Authorization: `Bearer ${token}` };

  await seedTokenIntoPage(page, token);
  await page.goto('/admin/categories');

  // Cancel first: a form that will not close would make every later step
  // fail for the wrong reason.
  await page.getByRole('button', { name: 'ახალი კატეგორია' }).click();
  await expect(page.locator('form')).toBeVisible();
  await page.getByRole('button', { name: 'გაუქმება' }).click();
  await expect(page.locator('form')).toHaveCount(0);

  // --- the parent --------------------------------------------------------
  const parentName = `E2E მშობელი ${id}`;
  await page.getByRole('button', { name: 'ახალი კატეგორია' }).click();
  const form = page.locator('form');
  await form.locator('input[type="text"]').first().fill(parentName);
  await form.getByPlaceholder('მაგ: mobile, iptv, billing').fill(`e2e-parent-${id}`);
  await form.getByPlaceholder('მაგ: fa-wifi, fa-tv, fa-sim-card').fill('fa-wifi');
  await form.locator('select').first().selectOption('mobile');
  await page.getByRole('button', { name: 'შენახვა' }).click();

  // Not just "the row containing the parent's name": once the tree is
  // expanded, a CHILD row shows its parent's name in the parent column
  // (admin-categories-page.html:164), so that filter matches two rows and
  // the delete below fails on a strict-mode violation. The subcategory
  // badge is what tells them apart.
  const parentRow = page.locator('tr').filter({ hasText: parentName }).filter({
    hasNotText: 'ქვე-კატეგორია'
  });
  await expect(parentRow).toHaveCount(1);

  const listing = async () => (await (await request.get('/api/categories', { headers: auth })).json());
  const byName = (all: { name: string }[], name: string) => all.find((c) => c.name === name);

  let all = await listing();
  const parent = byName(all, parentName);
  expect(parent, 'the created parent is not in /api/categories').toBeTruthy();
  expect(parent.slug, 'the slug field did not reach the payload').toBe(`e2e-parent-${id}`);
  expect(parent.icon, 'the icon field did not reach the payload').toBe('fa-wifi');
  expect(parent.pastel_color_class, 'the colour select did not reach the payload').toBe('mobile');
  expect(parent.parent_id, 'a top-level category has no parent').toBeNull();

  // --- the child ---------------------------------------------------------
  const childName = `E2E შვილი ${id}`;
  await page.getByRole('button', { name: 'ახალი კატეგორია' }).click();
  await form.locator('input[type="text"]').first().fill(childName);
  await form.locator('select').nth(1).selectOption({ label: parentName });
  await page.getByRole('button', { name: 'შენახვა' }).click();

  // A child is hidden until its parent is expanded -- that collapse is the
  // whole point of the tree, so assert it before expanding.
  await expect(page.locator('tr', { hasText: childName })).toHaveCount(0);
  await parentRow.getByRole('button').first().click();          // chevron
  const childRow = page.locator('tr', { hasText: childName });
  await expect(childRow).toHaveCount(1);
  await expect(childRow.getByText('ქვე-კატეგორია')).toBeVisible();

  all = await listing();
  const child = byName(all, childName);
  expect(child.parent_id, 'the parent select did not reach the payload').toBe(parent.id);

  // --- edit the child ----------------------------------------------------
  const renamed = `${childName} გადარქმეული`;
  await childRow.getByRole('button', { name: 'რედაქტ.' }).click();
  // The edit form must arrive holding the category, not empty.
  await expect(form.locator('input[type="text"]').first()).toHaveValue(childName);
  await form.locator('input[type="text"]').first().fill(renamed);
  await page.getByRole('button', { name: 'შენახვა' }).click();
  await expect(page.locator('tr', { hasText: renamed })).toHaveCount(1);
  expect(byName(await listing(), renamed).id, 'the edit created a new row instead of updating').toBe(
    child.id
  );

  // --- delete the parent, and meet the orphan ---------------------------
  page.once('dialog', (dialog) => dialog.accept());
  await parentRow.getByRole('button', { name: 'წაშლა' }).click();
  await expect(page.locator('tr', { hasText: parentName })).toHaveCount(0);

  // The child outlived its parent and is now flagged, not silently lost.
  const orphanRow = page.locator('tr', { hasText: renamed });
  await expect(orphanRow).toHaveCount(1);
  await expect(orphanRow.getByText('ობოლი')).toBeVisible();

  // Refresh must not make it disappear either -- this is server state, not a
  // rendering artefact.
  await page.getByRole('button', { name: 'განახლება' }).click();
  await expect(page.locator('tr', { hasText: renamed })).toHaveCount(1);

  // --- the orphan row's own controls ------------------------------------
  const orphanRenamed = `${renamed} საბოლოო`;   // not 'ობოლი' -- that is the badge's own text
  await orphanRow.getByRole('button', { name: 'რედაქტ.' }).click();
  await form.locator('input[type="text"]').first().fill(orphanRenamed);
  await page.getByRole('button', { name: 'შენახვა' }).click();
  await expect(page.locator('tr', { hasText: orphanRenamed })).toHaveCount(1);

  page.once('dialog', (dialog) => dialog.accept());
  await page.locator('tr', { hasText: orphanRenamed }).getByRole('button', { name: 'წაშლა' }).click();
  await expect(page.locator('tr', { hasText: orphanRenamed })).toHaveCount(0);

  expect(
    (await listing()).some((c: { id: number }) => c.id === child.id),
    'the row left the table but the category is still in the database'
  ).toBe(false);
});
