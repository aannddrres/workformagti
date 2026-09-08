import { test, expect } from '@playwright/test';
import { signInAsPersona } from './helpers';

test.describe('login', () => {
  test('local operator persona lands on the role-aware workspace', async ({ page }) => {
    // Clicking the role alone no longer signs anybody in -- it opens the
    // department/group/operator selects beneath it. signInAsPersona drives
    // the whole path; see its note for why specs name the account instead.
    const login = await signInAsPersona(page, 'tech@magti.ge');
    expect(login.status()).toBe(200);

    await expect(page).not.toHaveURL(/\/login/);
    await expect(page.getByRole('link', { name: 'მთავარი' })).toBeVisible();
  });

  test('local login picks a persona and never asks for a password', async ({ page }) => {
    await page.goto('/login');
    await expect(page.getByRole('button', { name: 'ოპერატორი', exact: true })).toBeVisible();
    await expect(page.getByText('პაროლი არ გამოიყენება')).toBeVisible();

    // The property worth pinning is that no password is ever collected here.
    // This used to also assert no e-mail field, and that assertion is gone
    // because the screen grew one on purpose: "სხვა ანგარიშით შესვლა"
    // (login.html:108) reaches a seeded account the picker cannot name. It
    // takes an address and no password, behind the same loopback gate, so it
    // does not weaken what this test was protecting.
    await expect(page.locator('input[type="password"]')).toHaveCount(0);
    await expect(page.locator('#uat-email')).toBeVisible();
  });
});
