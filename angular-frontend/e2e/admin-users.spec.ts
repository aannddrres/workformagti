import { test, expect, APIRequestContext } from '@playwright/test';
import { apiLogin, seedTokenIntoPage } from './helpers';

interface AdminUser {
  id: number;
  email: string;
  name: string;
  role: string;
  department: string | null;
  position: string | null;
  phone: string | null;
  team_id: number | null;
  permissions: string[];
  lock_version: number;
}

async function userByEmail(request: APIRequestContext, token: string, email: string): Promise<AdminUser> {
  const response = await request.get('/api/users', { headers: { Authorization: `Bearer ${token}` } });
  expect(response.ok()).toBeTruthy();
  return (await response.json()).find((user: AdminUser) => user.email === email)!;
}

test.describe('admin users', () => {
  test('directory-owned creation and password reset are fail-closed', async ({ page, request }) => {
    const token = await apiLogin(request, 'admin@magti.ge');
    const headers = { Authorization: `Bearer ${token}` };
    await seedTokenIntoPage(page, token);
    await page.goto('/admin/access');

    await expect(page.getByRole('heading', { name: 'მომხმარებლები', level: 1 })).toBeVisible();
    await expect(page.getByRole('button', { name: 'ახალი მომხმარებელი' })).toHaveCount(0);
    await expect(page.locator('input[type="password"]')).toHaveCount(0);

    const create = await request.post('/api/users', {
      headers,
      data: {
        email: 'blocked-local-user@magti.ge', name: 'Blocked', department: 'ტექნიკური',
        role: 'operator', password: 'NeverUsed123!'
      }
    });
    expect(create.status()).toBe(403);
    expect((await create.json()).detail).toContain('Active Directory');

    const target = await userByEmail(request, token, 'info@magti.ge');
    const reset = await request.post(`/api/users/${target.id}/reset-password`, {
      headers,
      data: { new_password: 'NeverUsed123!' }
    });
    expect(reset.status()).toBe(403);
    expect((await reset.json()).detail).toContain('Active Directory');
  });

  test('permission override saves while AD identity fields remain read-only', async ({ page, request }) => {
    test.setTimeout(120_000);
    const token = await apiLogin(request, 'admin@magti.ge');
    const headers = { Authorization: `Bearer ${token}` };
    const original = await userByEmail(request, token, 'info@magti.ge');
    expect(original.permissions).not.toContain('reports.export');

    try {
      await seedTokenIntoPage(page, token);
      await page.goto('/admin/access');
      await page.getByPlaceholder('სახელი, ელფოსტა ან დეპარტამენტი').fill(original.email);
      const row = page.locator('tbody tr', { hasText: original.email });
      await row.getByRole('button', { name: 'რედაქტირება' }).click();

      const modal = page.locator('app-user-edit-modal');
      await expect(modal.getByText(original.name, { exact: true })).toBeVisible();
      await expect(modal.locator('input[type="email"], input[type="text"], input[type="password"]')).toHaveCount(0);

      const override = modal.getByRole('combobox', { name: 'რეპორტების ექსპორტი' });
      await expect(override).toBeVisible();
      await override.selectOption('ALLOW');

      const [saved] = await Promise.all([
        page.waitForResponse((response) => /\/api\/users\/\d+$/.test(response.url()) && response.request().method() === 'PUT'),
        modal.getByRole('button', { name: 'შენახვა' }).click()
      ]);
      expect(saved.status()).toBe(200);
      await expect.poll(async () => (await userByEmail(request, token, original.email)).permissions)
        .toContain('reports.export');
    } finally {
      const current = await userByEmail(request, token, original.email);
      const restored = await request.put(`/api/users/${original.id}`, {
        headers,
        data: {
          role: original.role,
          department: original.department,
          position: original.position,
          phone: original.phone,
          team_id: original.team_id,
          lock_version: current.lock_version,
          overrides: [{ permission: 'reports.export', state: 'INHERIT' }]
        }
      });
      expect(restored.ok(), 'permission fixture must be restored').toBeTruthy();
    }
  });
});
