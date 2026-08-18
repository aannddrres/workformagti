import { test, expect } from '@playwright/test';
import { apiLogin, createCategory, runId, seedTokenIntoPage } from './helpers';

/**
 * News and videos, created / edited / deleted entirely through the admin UI.
 *
 * Both tabs had been clicked without error and neither had ever been shown
 * to write anything. The round trip is the assertion: what the form was set
 * to has to come back from the API, an edit has to overwrite it, and a
 * delete has to remove the row -- from the table AND from the database, not
 * just from the screen.
 *
 * Neither drawer uses Quill, so their fields can be addressed by index
 * without the trap that caught the article drawer.
 */

test.describe('admin content: news and videos', () => {
  test('news: create, edit and delete from the table', async ({ page, request }) => {
    test.setTimeout(90_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    const auth = { Authorization: `Bearer ${token}` };

    await seedTokenIntoPage(page, token);
    await page.goto('/admin/content');
    await page.getByRole('button', { name: 'სიახლეები', exact: true }).click();
    await page.getByRole('button', { name: '+ სიახლე' }).click();

    const drawer = page.locator('app-news-edit-drawer');
    await expect(drawer.getByText('ახალი სიახლის დამატება')).toBeVisible();

    const title = `E2E სიახლე ${id}`;
    const bodyText = `E2E სიახლის ტექსტი ${id}`;
    await drawer.locator('input[type="text"]').first().fill(title);
    await drawer.locator('textarea').fill(bodyText);
    await drawer.locator('select').selectOption('საინფორმაციო');

    // Mandatory reveals the due date, exactly as on the article drawer.
    const dueDate = drawer.locator('input[type="date"]');
    await expect(dueDate).toHaveCount(0);
    await drawer.locator('input[type="checkbox"]').first().check();
    await expect(dueDate).toBeVisible();
    await dueDate.fill('2030-03-10');

    await drawer.getByRole('button', { name: 'შენახვა' }).click();
    await expect(drawer).toHaveCount(0);

    const row = page.locator('tr', { hasText: title });
    await expect(row).toHaveCount(1);

    // --- the round trip ---------------------------------------------------
    // limit defaults to 20 (NewsController.java:118); ask for a wide page so
    // a failure here means the item is missing, not merely off the first page.
    const afterCreate = await request.get('/api/news?limit=200', { headers: auth });
    const created = (await afterCreate.json()).find((n: { title: string }) => n.title === title);
    expect(created, `the created news item is not in /api/news`).toBeTruthy();
    expect(created.content, 'the content textarea did not reach the payload').toContain(bodyText);
    expect(created.target_department, 'the department select did not reach the payload').toBe('საინფორმაციო');

    const reading = await request.get(
      `/api/compliance/required-readings/by-item/news/${created.id}`,
      { headers: auth }
    );
    expect(await reading.json(), 'a mandatory news item must create a required reading').not.toBeNull();

    // --- edit -------------------------------------------------------------
    const editedTitle = `${title} რედაქტირებული`;
    await row.getByRole('button').first().click();          // pencil
    const editDrawer = page.locator('app-news-edit-drawer');
    await expect(editDrawer.getByText('სიახლის რედაქტირება')).toBeVisible();
    // The drawer must arrive already holding the item, not empty.
    await expect(editDrawer.locator('input[type="text"]').first()).toHaveValue(title);
    await editDrawer.locator('input[type="text"]').first().fill(editedTitle);
    await editDrawer.getByRole('button', { name: 'შენახვა' }).click();
    await expect(editDrawer).toHaveCount(0);

    await expect(page.locator('tr', { hasText: editedTitle })).toHaveCount(1);
    const afterEdit = await request.get('/api/news?limit=200', { headers: auth });
    expect((await afterEdit.json()).find((n: { id: number }) => n.id === created.id).title).toBe(editedTitle);

    // --- delete -----------------------------------------------------------
    const editedRow = page.locator('tr', { hasText: editedTitle });
    page.once('dialog', (dialog) => dialog.accept());
    await editedRow.getByRole('button').last().click();     // trash
    await expect(editedRow).toHaveCount(0);

    const afterDelete = await request.get('/api/news?limit=200', { headers: auth });
    expect(
      (await afterDelete.json()).some((n: { id: number }) => n.id === created.id),
      'the row left the table but the news item is still in the database'
    ).toBe(false);
  });

  test('videos: create, edit and delete from the table', async ({ page, request }) => {
    test.setTimeout(90_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    const auth = { Authorization: `Bearer ${token}` };

    // The video category select offers category NAMES, so one has to exist.
    const category = await createCategory(request, token, `E2E ვიდეოს კატეგორია ${id}`);

    await seedTokenIntoPage(page, token);
    await page.goto('/admin/content');
    await page.getByRole('button', { name: 'ვიდეოები', exact: true }).click();
    await page.getByRole('button', { name: '+ ვიდეო' }).click();

    const drawer = page.locator('app-video-edit-drawer');
    await expect(drawer.getByText('ახალი ვიდეოს დამატება')).toBeVisible();

    const title = `E2E ვიდეო ${id}`;
    const url = `https://example.com/e2e-${id}.mp4`;
    await drawer.locator('input[type="text"]').first().fill(title);
    await drawer.getByPlaceholder('https://example.com/video.mp4').fill(url);
    await drawer.locator('select').first().selectOption(category.name);
    await drawer.locator('select').nth(1).selectOption('ტექნიკური');

    await drawer.getByRole('button', { name: 'შენახვა' }).click();
    await expect(drawer).toHaveCount(0);

    const row = page.locator('tr', { hasText: title });
    await expect(row).toHaveCount(1);

    const afterCreate = await request.get('/api/videos?limit=200', { headers: auth });
    const created = (await afterCreate.json()).find((v: { title: string }) => v.title === title);
    expect(created, 'the created video is not in /api/videos').toBeTruthy();
    expect(created.video_url, 'the URL field did not reach the payload').toBe(url);
    expect(created.category, 'the category select did not reach the payload').toBe(category.name);
    expect(created.target_department, 'the department select did not reach the payload').toBe('ტექნიკური');

    // --- edit -------------------------------------------------------------
    const editedTitle = `${title} რედაქტირებული`;
    await row.getByRole('button').first().click();
    const editDrawer = page.locator('app-video-edit-drawer');
    await expect(editDrawer.getByText('ვიდეოს რედაქტირება')).toBeVisible();
    await expect(editDrawer.locator('input[type="text"]').first()).toHaveValue(title);
    await editDrawer.locator('input[type="text"]').first().fill(editedTitle);
    await editDrawer.getByRole('button', { name: 'შენახვა' }).click();
    await expect(editDrawer).toHaveCount(0);

    await expect(page.locator('tr', { hasText: editedTitle })).toHaveCount(1);

    // --- delete -----------------------------------------------------------
    const editedRow = page.locator('tr', { hasText: editedTitle });
    page.once('dialog', (dialog) => dialog.accept());
    await editedRow.getByRole('button').last().click();
    await expect(editedRow).toHaveCount(0);

    const afterDelete = await request.get('/api/videos?limit=200', { headers: auth });
    expect(
      (await afterDelete.json()).some((v: { id: number }) => v.id === created.id),
      'the row left the table but the video is still in the database'
    ).toBe(false);
  });
});
