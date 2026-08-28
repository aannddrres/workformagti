import { test, expect } from '@playwright/test';
import { apiLogin, createArticle, firstCategoryId, markRead, runId, seedTokenIntoPage, syncRequiredReading } from './helpers';

async function exportedEvidenceCount(request: import('@playwright/test').APIRequestContext, headers: Record<string, string>): Promise<number> {
  const submitted = await request.post('/api/admin/exports/read-evidence', { headers });
  expect(submitted.status()).toBe(202);
  const audit = await request.get('/api/audit-logs?limit=1&offset=0&action=EXPORT_ADMIN_READ_EVIDENCE', { headers });
  expect(audit.ok()).toBeTruthy();
  const rows = await audit.json();
  expect(rows.length).toBe(1);
  return Number(JSON.parse(rows[0].details).row_count);
}

test('trash preserves mandatory-reading and read-receipt evidence', async ({ page, request }) => {
  test.setTimeout(120_000);
  const id = runId();
  const adminToken = await apiLogin(request, 'admin@magti.ge');
  const operatorToken = await apiLogin(request, 'nino@magti.ge');
  const headers = { Authorization: `Bearer ${adminToken}` };
  const categoryId = await firstCategoryId(request, adminToken);
  const title = `E2E evidence-preserving trash ${id}`;
  const articleId = await createArticle(request, adminToken, {
    title, categoryId, targetDepartments: ['Support']
  });
  const readingId = await syncRequiredReading(
    request, adminToken, articleId, 'Support', new Date(Date.now() + 7 * 86_400_000).toISOString()
  );
  await markRead(request, operatorToken, readingId);

  const before = await request.get(`/api/articles/${articleId}/read-receipts`, { headers });
  expect(before.ok()).toBeTruthy();
  const beforeBody = await before.json();
  expect(JSON.stringify(beforeBody)).toContain('ნინო ჩიტიშვილი');
  const evidenceCountBeforeTrash = await exportedEvidenceCount(request, headers);

  await seedTokenIntoPage(page, adminToken);
  await page.goto('/admin/content?type=article');
  await page.getByLabel('ძიება').fill(title);
  const row = page.locator('tbody tr', { hasText: title });
  await expect(row).toBeVisible();

  await row.getByRole('button', { name: 'სტატიის მოქმედებები' }).click();
  page.once('dialog', (dialog) => dialog.accept());
  await row.getByRole('button', { name: 'დაარქივება' }).click();
  await expect(row.getByText('არქივი')).toBeVisible();

  await row.getByRole('button', { name: 'სტატიის მოქმედებები' }).click();
  page.once('dialog', (dialog) => dialog.accept());
  const [trashed] = await Promise.all([
    page.waitForResponse((response) => response.url().endsWith(`/api/articles/${articleId}`) && response.request().method() === 'DELETE'),
    row.getByRole('button', { name: 'სანაგვეში გადატანა' }).click()
  ]);
  expect(trashed.status()).toBe(204);
  await expect(row).toHaveCount(0);

  const trash = await request.get('/api/content-trash', { headers });
  expect(trash.ok()).toBeTruthy();
  expect((await trash.json()).some((item: { item_type: string; item_id: number }) =>
    item.item_type === 'article' && item.item_id === articleId)).toBe(true);

  // The active-content endpoint correctly stops exposing a trashed article,
  // while the classified evidence export must retain the exact same rows.
  const after = await request.get(`/api/articles/${articleId}/read-receipts`, { headers });
  expect(after.status()).toBe(404);
  expect(await exportedEvidenceCount(request, headers)).toBe(evidenceCountBeforeTrash);
});
