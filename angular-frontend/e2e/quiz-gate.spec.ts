import { test, expect } from '@playwright/test';
import { apiLogin, createArticle, firstCategoryId, loginAsUi, runId, setQuiz, syncRequiredReading } from './helpers';

/**
 * Flagship scenario from the migration plan: mandatory reading -> quiz gate
 * -> pass -> auto mark-read. Fixture article/quiz/required-reading are
 * seeded via the API (as admin) rather than driven through the admin UI's
 * Quill/QuizBuilder widgets, which are exercised separately by the Vitest
 * unit specs; this file focuses on the reader-facing gate behavior.
 *
 * The fixture operator is a fresh `test_operator_*` email, which JIT-
 * provisions on first login with department "Support" (AuthenticationService's
 * default override) -- the fixture article targets that same department so
 * no admin-side user/department setup is required.
 */
test('mandatory reading with a quiz: gate blocks mark-read, passing unlocks it', async ({ page, request }) => {
  const id = runId();
  const adminToken = await apiLogin(request, 'admin@magti.ge');
  const categoryId = await firstCategoryId(request, adminToken);

  const title = `E2E ქვიზის სტატია ${id}`;
  const articleId = await createArticle(request, adminToken, {
    title,
    categoryId,
    targetDepartments: ['Support'],
    quizEnabled: true
  });

  const correctAnswer = 'სწორი პასუხი';
  await setQuiz(request, adminToken, articleId, 'რომელია სწორი პასუხი?', ['არასწორი პასუხი', correctAnswer], 1);

  const dueDate = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString();
  await syncRequiredReading(request, adminToken, articleId, 'Support', dueDate);

  await loginAsUi(page, `test_operator_quiz_${id}@magti.ge`);

  await page.goto('/reading');
  const row = page.locator('div[role="button"]', { hasText: title });
  await expect(row).toBeVisible();

  await row.getByRole('button', { name: 'წავიკითხე და გავიგე' }).click();

  await expect(page.getByText('დასადასტურებლად საჭიროა ქვიზის ჩაბარება')).toBeVisible();
  await page.getByRole('button', { name: 'ქვიზის დაწყება' }).click();

  await expect(page.getByText('რომელია სწორი პასუხი?')).toBeVisible();
  await page.getByText(correctAnswer, { exact: true }).click();
  await page.getByRole('button', { name: 'დასრულება' }).click();

  await expect(row.getByText('წაკითხულია')).toBeVisible();
});
