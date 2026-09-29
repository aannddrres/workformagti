import { test, expect } from '@playwright/test';
import { acceptConfirmation, apiLogin, runId, seedTokenIntoPage } from './helpers';

/**
 * The category tree: create a parent and a child, expand, edit, and then take
 * the pair apart in the only order the backend allows.
 *
 * It is one test rather than three because each step needs the state the last
 * one left. The ending changed on 2026-08-28: this used to delete the parent
 * and assert on the "orphan" row that left behind, and c541c58 made that
 * impossible -- a parent with an active child is refused with 409
 * (CategoryController.java:173), precisely so nothing is ever stranded. The
 * orphan row still exists in the template for data that predates the guard,
 * and nothing here can reach it any more; that is the honest position, not a
 * gap to paper over with a fixture the product cannot produce.
 */
test('categories: create, nest, expand, edit, and the delete order the tree allows', async ({
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
  await childRow.getByRole('button', { name: 'რედაქტირება' }).click();
  // The edit form must arrive holding the category, not empty.
  await expect(form.locator('input[type="text"]').first()).toHaveValue(childName);
  await form.locator('input[type="text"]').first().fill(renamed);
  await page.getByRole('button', { name: 'შენახვა' }).click();
  await expect(page.locator('tr', { hasText: renamed })).toHaveCount(1);
  expect(byName(await listing(), renamed).id, 'the edit created a new row instead of updating').toBe(
    child.id
  );

  // --- deleting a parent that still has a child is refused --------------
  // Until c541c58 (2026-08-28) this succeeded and stranded the child as an
  // "orphan". The subcategory guard (CategoryController.java:173) answers
  // 409 now, on purpose, so that orphan state can no longer be produced
  // through the product at all -- and the assertions that used to follow it
  // here could not pass against any build newer than that fix.
  const [refused] = await Promise.all([
    page.waitForResponse(
      (r) => /\/api\/categories\/\d+$/.test(r.url()) && r.request().method() === 'DELETE'
    ),
    parentRow
      .getByRole('button', { name: 'კატეგორიის მოქმედებები' })
      .click()
      .then(() => parentRow.getByRole('button', { name: 'წაშლა' }).click())
      .then(() => acceptConfirmation(page))
  ]);
  expect(refused.status(), 'a parent with an active child must not be deletable').toBe(409);

  // The refusal leaves the page on its generic error banner; a refresh has to
  // bring back a tree that never changed.
  await page.getByRole('button', { name: 'განახლება' }).click();
  await expect(parentRow).toHaveCount(1);
  await expect(page.locator('tr', { hasText: renamed })).toHaveCount(1);

  // --- child first, then the parent: the order the guard leaves open -----
  await page.locator('tr', { hasText: renamed }).getByRole('button', { name: 'კატეგორიის მოქმედებები' }).click();
  await page.locator('tr', { hasText: renamed }).getByRole('button', { name: 'წაშლა' }).click();
  await acceptConfirmation(page);
  await expect(page.locator('tr', { hasText: renamed })).toHaveCount(0);

  await parentRow.getByRole('button', { name: 'კატეგორიის მოქმედებები' }).click();
  await parentRow.getByRole('button', { name: 'წაშლა' }).click();
  await acceptConfirmation(page);
  await expect(parentRow).toHaveCount(0);

  const remaining = await listing();
  expect(
    remaining.some((c: { id: number }) => c.id === child.id),
    'the child row left the table but the category is still in the database'
  ).toBe(false);
  expect(
    remaining.some((c: { id: number }) => c.id === parent.id),
    'the parent row left the table but the category is still in the database'
  ).toBe(false);
});
