import { test, expect } from '@playwright/test';
import { apiLogin, firstCategoryId, runId, seedTokenIntoPage } from './helpers';

/**
 * Port of the Vitest ArticleEditDrawer spec's due-date guard, driven
 * through the real UI this time: native `required` on the title input
 * means the browser blocks the submit event entirely for an empty title,
 * so Angular's submit() guard order (department -> due-date) is only
 * reachable once a title is present, which this test fills in.
 *
 * The category `<select>` (article-edit-drawer.html:41-49) is `required` too,
 * and its options come from the database. Against a schema Flyway has just
 * created there are none, so the select is invalid with no way for the test
 * to make it valid, and Chrome refuses to fire the submit event at all --
 * submit() never runs and nothing is under test. That is what this spec's
 * first CI run hit. Seeding one category through the API is the fixture, not
 * the subject.
 */
test('admin content: mandatory article with no due date is blocked from saving', async ({ page, request }) => {
  const id = runId();
  // One login, used for both the fixture and the UI session: the backend
  // rate-limits /api/auth/login to 10/minute per IP and every spec here
  // shares one.
  const token = await apiLogin(request, 'content@magti.ge');
  await firstCategoryId(request, token);
  await seedTokenIntoPage(page, token);

  await page.goto('/admin/content');
  await page.getByRole('button', { name: 'სტატია', exact: true }).click();

  // The host <app-article-edit-drawer> element itself has a zero-size box
  // (its children are position:fixed, out of normal flow) so it never
  // registers as "visible" to Playwright -- assert on a real descendant
  // instead, then keep using `drawer` to scope child locators.
  const drawer = page.locator('app-article-edit-drawer');
  await expect(drawer.getByText('ახალი სტატიის დამატება')).toBeVisible();

  await drawer.locator('input[type="text"]').first().fill(`E2E due-date test ${id}`);

  // submit() checks department BEFORE due date and returns at the first
  // failure, so an unchecked department here would silently produce exactly
  // the symptom this test is meant to detect. Assert it, so a fixture problem
  // can never be read as the guard under test.
  const department = drawer.locator('label', { hasText: 'საინფორმაციო' }).locator('input[type="checkbox"]');
  await department.check();
  await expect(department).toBeChecked();

  const mandatoryCheckbox = drawer.locator('input[type="checkbox"]').first();
  await mandatoryCheckbox.click();
  await expect(mandatoryCheckbox).toBeChecked();

  // Due date is deliberately left empty.
  await drawer.getByRole('button', { name: 'შენახვა' }).click();

  await expect(drawer.getByText('სავალდებულო მასალას უნდა ჰქონდეს გაცნობის ვადა')).toBeVisible();
  // Drawer must still be open -- the save request never fired.
  await expect(drawer.getByText('ახალი სტატიის დამატება')).toBeVisible();
});
