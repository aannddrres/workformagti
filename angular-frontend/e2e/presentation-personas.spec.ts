import { test, expect } from '@playwright/test';
import { apiLogin, seedTokenIntoPage, signInAsPersona } from './helpers';

// Where each persona is meant to land. The clicks that get there live in
// signInAsPersona -- nino@magti.ge arrives through the screen's e-mail
// escape hatch because the picker's first operator slot is tech@ or info@,
// and the other three through the cascading selects.
const PRESENTATION_PERSONAS = [
  { email: 'nino@magti.ge', landing: /\/$/ },
  { email: 'manager@magti.ge', landing: /\/manager$/ },
  { email: 'content@magti.ge', landing: /\/admin\/content$/ },
  { email: 'admin@magti.ge', landing: /\/admin\/overview$/ }
];

test.describe('presentation personas', () => {
  for (const persona of PRESENTATION_PERSONAS) {
    test(`${persona.email} authenticates through the loopback persona chooser`, async ({ page }) => {
      const login = await signInAsPersona(page, persona.email);

      expect(login.status()).toBe(200);
      await expect(page).toHaveURL(persona.landing);
      await expect(page.getByRole('button', { name: 'ანგარიშის მენიუ' })).toBeVisible();
    });
  }

  // This screen must never collect a password, whichever path signs you in.
  // It replaces an assertion that no e-mail field existed either, which the
  // escape hatch has made false on purpose (login.html:108).
  test('no path through the chooser asks for a password', async ({ page }) => {
    await page.goto('/login');
    await expect(page.locator('input[type="password"]')).toHaveCount(0);
    await page.getByRole('button', { name: 'ოპერატორი', exact: true }).click();
    await expect(page.locator('input[type="password"]')).toHaveCount(0);
  });

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
