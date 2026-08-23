import { test, expect } from '@playwright/test';

test.describe('login', () => {
  test('valid JIT test-account credentials land on the dashboard', async ({ page }) => {
    await page.goto('/login');
    await page.locator('#login-email').fill('info@magti.ge');
    await page.locator('#login-password').fill('x');
    await page.getByRole('button', { name: 'შესვლა' }).click();

    await expect(page).not.toHaveURL(/\/login/);
    // The sidebar link, specifically. The redesign put the current page's
    // title in the header too, so a bare text match now finds two.
    await expect(page.getByRole('link', { name: 'მთავარი' })).toBeVisible();
  });

  test('unknown account is rejected with an error and stays on the login page', async ({ page }) => {
    await page.goto('/login');
    await page.locator('#login-email').fill('nobody-e2e@notreal.ge');
    await page.locator('#login-password').fill('wrong-password');
    await page.getByRole('button', { name: 'შესვლა' }).click();

    await expect(page).toHaveURL(/\/login/);
    await expect(page.locator('p.text-red-600')).toBeVisible();
  });
});
