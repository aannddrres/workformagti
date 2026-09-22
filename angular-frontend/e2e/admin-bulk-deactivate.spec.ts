import { test, expect } from '@playwright/test';
import { acceptConfirmation, apiLogin, seedTokenIntoPage } from './helpers';

/**
 * PO-24's leaver sweep, end to end. The unit tests pin the selection rules;
 * this proves the whole path an administrator walks once a month -- find the
 * person, tick them, confirm -- actually switches the account off, to the
 * point where that person's next sign-in is refused.
 */
test.describe('admin leaver sweep (PO-24)', () => {
  test('an account swept from the users table can no longer sign in', async ({ page, request }) => {
    const adminToken = await apiLogin(request, 'admin@magti.ge');
    // Created on first sign-in through the development login, so the test
    // owns a person nobody else's scenario depends on.
    const leaver = `test_operator_po24_${Date.now()}@magti.ge`;
    await apiLogin(request, leaver);

    await seedTokenIntoPage(page, adminToken);
    await page.goto('/admin/access');
    await page.getByPlaceholder('სახელი, ელფოსტა ან დეპარტამენტი').fill(leaver);

    const row = page.getByRole('row').filter({ hasText: leaver });
    await expect(row).toHaveCount(1);
    await row.getByRole('checkbox').check();
    await expect(page.getByText('მონიშნულია 1 ანგარიში')).toBeVisible();

    await page.getByRole('button', { name: 'მონიშნულების გათიშვა' }).click();
    await acceptConfirmation(page);

    await expect(page.getByText('გაითიშა 1 ანგარიში')).toBeVisible();
    await expect(row).toContainText('გათიშული');
    // Off accounts are not offered to a second sweep.
    await expect(row.getByRole('checkbox')).toHaveCount(0);

    const retry = await request.post('/api/auth/login', { data: { email: leaver, password: 'anything' } });
    expect(retry.status()).toBe(401);
  });
});
