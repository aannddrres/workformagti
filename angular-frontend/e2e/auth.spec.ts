import { test, expect } from '@playwright/test';
import { signInAsPersona } from './helpers';

test.describe('login', () => {
  test('a development account signs in through the standard form', async ({ page }) => {
    const login = await signInAsPersona(page, 'tech@magti.ge');
    expect(login.status()).toBe(200);

    await expect(page).not.toHaveURL(/\/login/);
    await expect(page.getByRole('link', { name: 'მთავარი' })).toBeVisible();
  });

  test('the login screen is the standard form, on loopback too', async ({ page }) => {
    await page.goto('/login');

    // The persona picker and its password-less address field showed on
    // loopback until the production handover. One form everywhere now.
    await expect(page.locator('#login-email')).toBeVisible();
    await expect(page.locator('#login-password')).toHaveAttribute('type', 'password');
    await expect(page.locator('#uat-email')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'ოპერატორი', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button')).toHaveCount(1);
  });
});
