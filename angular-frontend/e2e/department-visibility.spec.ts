import { test, expect } from '@playwright/test';
import { apiLogin, createArticle, firstCategoryId, runId, seedTokenIntoPage } from './helpers';

test('video attachments enforce the same department audience on a direct URL', async ({ request }) => {
  const admin = await apiLogin(request, 'admin@magti.ge');
  const auth = { Authorization: `Bearer ${admin}` };
  const upload = await request.post('/api/upload', { headers: auth, multipart: { file: {
    name: 'video-attachment.png', mimeType: 'image/png',
    buffer: Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 1, 2]),
  } } });
  expect(upload.status()).toBe(200);
  const file = await upload.json();
  const created = await request.post('/api/videos', { headers: auth, data: {
    title: `E2E ვიდეოს წვდომა ${runId()}`, video_url: file.url, target_department: 'ტექნიკური',
  } });
  expect(created.status()).toBe(200);
  const video = await created.json();
  for (const [email, visible] of [['tech@magti.ge', true], ['info@magti.ge', false]] as const) {
    const token = await apiLogin(request, email);
    const headers = { Authorization: `Bearer ${token}` };
    const listed = await request.get('/api/videos', { headers });
    expect((await listed.json()).some((item: { id: number }) => item.id === video.id)).toBe(visible);
    expect((await request.get(file.url, { headers })).status()).toBe(visible ? 200 : 404);
  }
});

/**
 * Confirms department-scoped visibility actually holds in the UI, not just
 * at the API layer (already covered by Java integration tests): an
 * operator in one real department sees an article targeted at their
 * department and not one targeted at a different department, and vice
 * versa. Uses real Georgian department strings (matching DEPARTMENT_ORDER
 * in article-edit-drawer.ts) rather than the JIT test personas' English
 * placeholder departments, which don't match real dept-targeting values
 * (see the dept-group-hierarchy-in-free-text memory).
 */
test('operators in different departments see different mandatory-reading content', async ({ page, request }) => {
  const id = runId();
  const adminToken = await apiLogin(request, 'admin@magti.ge');
  const categoryId = await firstCategoryId(request, adminToken);

  const infoTitle = `E2E საინფორმაციო-სტატია ${id}`;
  const techTitle = `E2E ტექნიკური-სტატია ${id}`;
  await createArticle(request, adminToken, { title: infoTitle, categoryId, targetDepartments: ['საინფორმაციო'] });
  await createArticle(request, adminToken, { title: techTitle, categoryId, targetDepartments: ['ტექნიკური'] });

  // Identity and department ownership come from the seeded directory
  // personas; the portal must not manufacture local users for a test.
  const infoToken = await apiLogin(request, 'info@magti.ge');
  const techToken = await apiLogin(request, 'tech@magti.ge');

  await seedTokenIntoPage(page, infoToken);
  // The shared local Oracle retains older E2E fixtures and the knowledge
  // base deliberately renders only the first 40 title-sorted cards. Search
  // by this run's common id so the assertion tests department visibility,
  // not whether the new rows happen to sort above accumulated fixtures.
  await page.goto(`/info?q=${encodeURIComponent(id)}`);
  await expect(page.getByText(infoTitle)).toBeVisible();
  await expect(page.getByText(techTitle)).not.toBeVisible();

  await seedTokenIntoPage(page, techToken);
  await page.goto(`/info?q=${encodeURIComponent(id)}`);
  await expect(page.getByText(techTitle)).toBeVisible();
  await expect(page.getByText(infoTitle)).not.toBeVisible();
});
