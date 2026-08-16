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
 *
 * WHERE CONFIRMATION LIVES
 * This spec used to click a "წავიკითხე და გავიგე" button on the /reading list
 * row. That button is gone on purpose, not by accident: confirming from the
 * list cleared a compliance obligation in one click without the article ever
 * being opened, so it now sits at the END of the material itself
 * (app-reading-confirm, and see its class docstring for the full argument).
 * The gate is the same gate; only the place you reach it from moved, and this
 * spec follows the operator's real path -- list row -> article -> confirm.
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

  // The row only opens the item now -- it cannot mark it read.
  await row.click();
  await expect(page).toHaveURL(new RegExp(`/article/${articleId}$`));

  const confirm = page.locator('app-reading-confirm');
  await confirm.getByRole('button', { name: 'წავიკითხე' }).click();

  // mark-read answers 403 (quiz not passed), which opens the quiz directly --
  // there is no separate "start the quiz" step to click through.
  await expect(page.getByText('რომელია სწორი პასუხი?')).toBeVisible();
  // exact, because 'არასწორი პასუხი' contains 'სწორი პასუხი'.
  await page.getByText(correctAnswer, { exact: true }).click();
  await page.getByRole('button', { name: 'დასრულება' }).click();

  // Passing re-issues mark-read, which now succeeds: the same panel flips to
  // the confirmed state.
  await expect(confirm.getByText('გაცნობა დადასტურებულია')).toBeVisible();

  // ...and the obligation reads as cleared back on the list.
  await page.goto('/reading');
  await expect(page.locator('div[role="button"]', { hasText: title }).getByText('წაკითხულია')).toBeVisible();
});
