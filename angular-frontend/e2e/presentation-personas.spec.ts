import { test, expect } from '@playwright/test';
import { apiLogin, seedTokenIntoPage } from './helpers';

const PRESENTATION_PERSONAS = [
  { email: 'nino@magti.ge', label: 'ოპერატორი', landing: /\/$/ },
  { email: 'manager@magti.ge', label: 'მენეჯერი', landing: /\/manager$/ },
  { email: 'content@magti.ge', label: 'კონტენტ-ადმინი', landing: /\/admin\/content$/ },
  { email: 'admin@magti.ge', label: 'სისტემური ადმინი', landing: /\/admin\/overview$/ }
];

test.describe('presentation personas', () => {
  for (const persona of PRESENTATION_PERSONAS) {
    test(`${persona.email} authenticates through the loopback persona chooser`, async ({ page }) => {
      await page.goto('/login');
      await expect(page.locator('input[type="email"], input[type="password"]')).toHaveCount(0);

      const [login] = await Promise.all([
        page.waitForResponse((response) => response.url().includes('/api/auth/login')),
        page.getByRole('button', { name: persona.label, exact: true }).click()
      ]);

      expect(login.status()).toBe(200);
      await expect(page).toHaveURL(persona.landing);
      await expect(page.getByRole('button', { name: 'ანგარიშის მენიუ' })).toBeVisible();
    });
  }

  test('operator is redirected away from administration', async ({ page }) => {
    const token = await apiLogin(page.request, 'info@magti.ge');
    await seedTokenIntoPage(page, token);
    await page.goto('/admin/content');
    await expect(page).not.toHaveURL(/\/admin\/content$/);
  });

  test('content admin reaches content management but not system administration', async ({ page }) => {
    const token = await apiLogin(page.request, 'content@magti.ge');
    await seedTokenIntoPage(page, token);

    await page.goto('/admin/content');
    await expect(page).toHaveURL(/\/admin\/content$/);
    await expect(page.getByRole('tab', { name: 'სტატიები' })).toBeVisible();
    await expect(page.locator('button.primary-button', { hasText: 'სტატია' })).toBeVisible();

    await page.goto('/admin/access');
    await expect(page).not.toHaveURL(/\/admin\/access$/);
  });

  test('manager reaches scoped statistics but not raw audit or user administration', async ({ page }) => {
    const token = await apiLogin(page.request, 'manager@magti.ge');
    await seedTokenIntoPage(page, token);

    const [stats] = await Promise.all([
      page.waitForResponse((response) => response.url().includes('/api/manager/department-stats')),
      page.goto('/manager')
    ]);
    expect(stats.status()).toBe(200);
    await expect(page).toHaveURL(/\/manager$/);

    await page.goto('/admin/audit');
    await expect(page).toHaveURL(/\/forbidden$/);
    await expect(page.getByRole('heading', { name: 'წვდომა შეზღუდულია' })).toBeVisible();

    await page.goto('/admin/access');
    await expect(page).not.toHaveURL(/\/admin\/access$/);
  });

  test('system admin reaches user access and organisation management', async ({ page }) => {
    const token = await apiLogin(page.request, 'admin@magti.ge');
    await seedTokenIntoPage(page, token);

    const [users] = await Promise.all([
      page.waitForResponse((response) => response.url().includes('/api/users')),
      page.goto('/admin/access')
    ]);
    expect(users.status()).toBe(200);
    await expect(page).toHaveURL(/\/admin\/access$/);

    const [organisation] = await Promise.all([
      page.waitForResponse((response) => response.url().includes('/api/admin/org/structure')),
      page.goto('/admin/org')
    ]);
    expect(organisation.status()).toBe(200);
    await expect(page).toHaveURL(/\/admin\/org$/);
  });
});
