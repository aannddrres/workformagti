import { test, expect } from '@playwright/test';
import { apiLogin, createCategory, runId, seedTokenIntoPage } from './helpers';

/**
 * Every control on the article drawer, checked by what it produces rather
 * than by whether it can be clicked.
 *
 * The click sweep had already pressed all of these without an error. That
 * says the handlers are wired; it says nothing about whether the value
 * reaches the saved article. So this fills the form through the UI and then
 * reads the article back over the API: category, tags, scheduled status and
 * its timestamp, the quiz flag, and the required-reading row with its due
 * date. A control whose value never arrives fails here, and only here.
 */
test('article drawer: what the form is set to is what gets saved', async ({ page, request }) => {
  test.setTimeout(90_000);
  const id = runId();
  const token = await apiLogin(request, 'content@magti.ge');
  const category = await createCategory(request, token, `E2E დრაუერის კატეგორია ${id}`);

  await seedTokenIntoPage(page, token);
  await page.goto('/admin/content');
  await page.getByRole('button', { name: 'სტატია', exact: true }).click();

  const drawer = page.locator('app-article-edit-drawer');
  await expect(drawer.getByText('ახალი სტატიის დამატება')).toBeVisible();

  const title = `E2E დრაუერი ${id}`;
  await drawer.locator('input[type="text"]').first().fill(title);

  // The live preview is the title field's only immediate consequence, so it
  // is the only thing that can show the binding works before the save.
  const previewTitle = drawer.locator('h2', { hasText: title });
  await expect(previewTitle).toBeVisible();
  // The frame is the element previewFrameClass() sizes: h2 -> .p-5 -> frame.
  const frame = previewTitle.locator('xpath=../..');

  // Quill, not a textarea -- typing into its contenteditable root is what a
  // real editor does, and the preview must follow it.
  const body = `E2E დრაუერის ტექსტი ${id}`;
  await drawer.locator('.ql-editor').fill(body);
  await expect(frame.getByText(body)).toBeVisible();

  // Quill's toolbar contributes its own <select>s (ql-header, ql-color, ...)
  // and they come FIRST in the DOM, because the editor sits above the rest
  // of the form. Counting selects from zero therefore addressed the editor,
  // not the form -- so the form's own selects are named by what they are not.
  const formSelects = drawer.locator('select:not([class*="ql-"])');
  const categorySelect = formSelects.first();
  await categorySelect.selectOption({ label: category.name });

  // Same trap for text inputs: Quill's link tooltip has one. The tags field
  // has a placeholder, so it can be addressed directly.
  const tags = `e2e,${id}`;
  await drawer.getByPlaceholder('ტეგები მძიმით გამოყოფილი').fill(tags);

  // --- scheduled publishing --------------------------------------------
  // The datetime field does not exist until the status says it should.
  const statusSelect = formSelects.nth(1);
  const scheduledAt = drawer.locator('input[type="datetime-local"]');
  await expect(scheduledAt).toHaveCount(0);
  await statusSelect.selectOption('scheduled');
  await expect(scheduledAt).toBeVisible();
  await scheduledAt.fill('2030-01-15T09:30');

  // --- department, mandatory + due date ---------------------------------
  const department = drawer.locator('label', { hasText: 'საინფორმაციო' }).locator('input[type="checkbox"]');
  await department.check();

  const dueDate = drawer.locator('input[type="date"]');
  await expect(dueDate).toHaveCount(0);
  const mandatory = drawer.locator('input[type="checkbox"]').first();
  await mandatory.check();
  await expect(dueDate).toBeVisible();
  await dueDate.fill('2030-02-20');

  // --- quiz --------------------------------------------------------------
  // The builder is the toggle's whole visible effect.
  await expect(drawer.locator('app-quiz-builder')).toHaveCount(0);
  const quizToggle = drawer.locator('input[type="checkbox"]').nth(1);
  await quizToggle.check();
  await expect(drawer.locator('app-quiz-builder')).toBeVisible();

  // --- preview device toggle --------------------------------------------
  // previewFrameClass() puts the mobile frame at a literal w-[360px]
  // (article-edit-drawer.ts:239-242), so the assertion is the measured
  // width, not the class string.
  await drawer.getByRole('button', { name: 'Mobile' }).click();
  await expect.poll(async () => (await frame.boundingBox())?.width).toBe(360);
  await drawer.getByRole('button', { name: 'Desktop' }).click();
  await expect.poll(async () => (await frame.boundingBox())?.width ?? 0).toBeGreaterThan(360);

  // --- notify operators, then save --------------------------------------
  // Addressed through its label rather than as "the last checkbox": the quiz
  // builder above it renders its own controls once it has a question.
  const notify = drawer
    .locator('label', { hasText: 'ოპერატორების შეტყობინება' })
    .locator('input[type="checkbox"]');
  await notify.check();

  await drawer.getByRole('button', { name: 'შენახვა' }).click();
  await expect(drawer).toHaveCount(0); // the drawer closes only on success

  // --- and now the only question that matters ---------------------------
  const list = await request.get('/api/articles', { headers: { Authorization: `Bearer ${token}` } });
  expect(list.ok(), `admin article list failed: ${list.status()}`).toBeTruthy();
  const saved = (await list.json()).find((a: { title: string }) => a.title === title);
  expect(saved, `the saved article "${title}" is not in the admin list`).toBeTruthy();

  expect(saved.category_id, 'the category select did not reach the payload').toBe(category.id);
  expect(saved.tags, 'the tags field did not reach the payload').toBe(tags);
  expect(saved.status, 'the status select did not reach the payload').toBe('scheduled');
  expect(saved.published_at, 'a scheduled article must carry its timestamp').toContain('2030-01-15');

  const detail = await request.get(`/api/articles/${saved.id}`, {
    headers: { Authorization: `Bearer ${token}` }
  });
  expect((await detail.json()).quiz_enabled, 'the quiz toggle did not reach the payload').toBe(true);

  // by-item answers with the single reading or a literal null -- not a list
  // (ComplianceController.java:258-273).
  const lookup = await request.get(
    `/api/compliance/required-readings/by-item/article/${saved.id}`,
    { headers: { Authorization: `Bearer ${token}` } }
  );
  expect(lookup.ok(), `by-item lookup failed: ${lookup.status()}`).toBeTruthy();
  const reading = await lookup.json();
  expect(reading, 'ticking "mandatory" must create a required reading').not.toBeNull();
  expect(reading.due_date, 'the due date did not reach the required reading').toContain('2030-02-20');
});
