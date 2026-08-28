import { test, expect } from '@playwright/test';
import { apiLogin, seedTokenIntoPage } from './helpers';

test('manager dashboard is scoped, interactive and export-fail-closed without a primary team', async ({ page, request }) => {
  test.setTimeout(120_000);
  const token = await apiLogin(request, 'manager@magti.ge');
  const headers = { Authorization: `Bearer ${token}` };

  const leadership = await request.get('/api/manager/leadership-options', { headers });
  expect(leadership.ok()).toBeTruthy();
  const leadershipBody = await leadership.json();
  expect(leadershipBody.canExportPrimary).toBe(false);

  await seedTokenIntoPage(page, token);
  await page.goto('/manager');
  await expect(page.getByRole('heading', { name: 'გუნდის სტატისტიკა' })).toBeVisible();

  const sortByCompliance = page.getByRole('button', { name: 'დალაგება: შესრულების მიხედვით' });
  await sortByCompliance.click();
  await expect(page.getByRole('button', { name: 'დალაგება: სახელის მიხედვით' })).toBeVisible();

  const [refreshed] = await Promise.all([
    page.waitForResponse((response) => response.url().includes('/api/manager/department-stats')),
    page.getByRole('button', { name: 'განახლება' }).click()
  ]);
  expect(refreshed.status()).toBe(200);

  const [critical] = await Promise.all([
    page.waitForResponse((response) => response.url().includes('/api/admin/critical-operators')),
    page.locator('div[role="button"]').first().click()
  ]);
  expect(critical.status()).toBe(200);
  const criticalDialog = page.getByRole('dialog');
  await expect(criticalDialog).toBeVisible();
  await criticalDialog.getByRole('button', { name: 'დახურვა' }).click();
  await expect(criticalDialog).toHaveCount(0);

  const groupCard = page.locator('div[role="button"]', { hasText: 'ტექნიკური' }).last();
  const [groupUsers] = await Promise.all([
    page.waitForResponse((response) => response.url().includes('/groups/')),
    groupCard.click()
  ]);
  expect(groupUsers.status()).toBe(200);
  const groupDialog = page.getByRole('dialog');
  await expect(groupDialog).toBeVisible();
  await expect(groupDialog.getByText('ნიკოლოზი აღდგომელაძე')).toBeVisible();
  await groupDialog.getByRole('button', { name: 'დახურვა' }).click();

  // A manager who has no canonical primary team (or is acting-only) must
  // never be offered exports; the server independently rejects the request.
  await expect(page.getByRole('button', { name: 'CSV', exact: true })).toHaveCount(0);
  const exportAttempt = await request.get('/api/export/readings', { headers });
  expect(exportAttempt.status()).toBe(403);
});
