import { test, expect } from '@playwright/test';
import { loginAsUi, runId } from './helpers';

/**
 * Port of the Vitest ArticleEditDrawer spec's due-date guard, driven
 * through the real UI this time: native `required` on the title input
 * means the browser blocks the submit event entirely for an empty title,
 * so Angular's submit() guard order (department -> due-date) is only
 * reachable once a title is present, which this test fills in.
 */
test('admin content: mandatory article with no due date is blocked from saving', async ({ page }) => {
  const id = runId();
  await loginAsUi(page, 'content@magti.ge');

  await page.goto('/admin/content');
  await page.getByRole('button', { name: '+ სტატია' }).click();

  // The host <app-article-edit-drawer> element itself has a zero-size box
  // (its children are position:fixed, out of normal flow) so it never
  // registers as "visible" to Playwright -- assert on a real descendant
  // instead, then keep using `drawer` to scope child locators.
  const drawer = page.locator('app-article-edit-drawer');
  await expect(drawer.getByText('ახალი სტატიის დამატება')).toBeVisible();

  await drawer.locator('input[type="text"]').first().fill(`E2E due-date test ${id}`);
  await drawer.locator('label', { hasText: 'საინფორმაციო' }).click();

  const mandatoryCheckbox = drawer.locator('input[type="checkbox"]').first();
  await mandatoryCheckbox.click();
  await expect(mandatoryCheckbox).toBeChecked();

  // Due date is deliberately left empty.
  await drawer.getByRole('button', { name: 'შენახვა' }).click();

  await expect(drawer.getByText('სავალდებულო მასალას უნდა ჰქონდეს გაცნობის ვადა')).toBeVisible();
  // Drawer must still be open -- the save request never fired.
  await expect(drawer.getByText('ახალი სტატიის დამატება')).toBeVisible();
});
