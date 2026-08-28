import { test, expect } from '@playwright/test';

test.describe('login', () => {
  test('local operator persona lands on the role-aware workspace', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: 'ოპერატორი' }).click();

    await expect(page).not.toHaveURL(/\/login/);
    await expect(page.getByRole('link', { name: 'მთავარი' })).toBeVisible();
  });

  test('local login exposes personas but no password or email fields', async ({ page }) => {
    await page.goto('/login');
    await expect(page.getByText('ლოკალური სატესტო პერსონა')).toBeVisible();
    await expect(page.locator('input[type="password"]')).toHaveCount(0);
    await expect(page.locator('input[type="email"]')).toHaveCount(0);
  });
});
