import { test, expect } from '@playwright/test';
import { apiLogin, createArticle, createCategory, runId, seedTokenIntoPage } from './helpers';

/**
 * The three components that appear on nearly every page: the global search
 * palette, the favourite star, and the quiz builder inside the article
 * drawer.
 *
 * These are the most-used controls in the product and the least covered --
 * being shared, no feature-area spec owns them, so each one was clicked by
 * the sweep and asserted by nobody.
 */
test.describe('shared components', () => {
  test('global search palette: type, navigate by keyboard, recover from failure', async ({
    page,
    request
  }) => {
    test.setTimeout(120_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    const category = await createCategory(request, token, `E2E ძებნის კატეგორია ${id}`);
    const title = `E2Eძებნა${id}`;
    const articleId = await createArticle(request, token, {
      title,
      categoryId: category.id,
      targetDepartments: ['საინფორმაციო']
    });

    await seedTokenIntoPage(page, token);
    await page.goto('/');

    await page.getByRole('button', { name: 'ძებნა', exact: true }).click();
    const palette = page.locator('div[role="dialog"]');
    await expect(palette).toBeVisible();

    const input = palette.getByPlaceholder('მოძებნე სტატია, სიახლე ან ვიდეო…');
    // The palette opens focused, so an operator can start typing immediately
    // -- that is the whole point of a Ctrl+K palette.
    await expect(input).toBeFocused();

    // One character is below SearchService.MIN_QUERY_LENGTH, and the palette
    // must say so rather than show an empty list, which would read as "found
    // nothing".
    await input.fill('E');
    await expect(palette.getByText('შეიყვანე მინიმუმ 2 სიმბოლო')).toBeVisible();

    await input.fill(title);
    const hit = palette.getByRole('option', { name: new RegExp(title) });
    await expect(hit).toBeVisible();

    // Clicking inside must not dismiss it; only the backdrop does. The target
    // is the palette's own magnifier -- decoration, inside the dialog, wired to
    // nothing. Clicking by COORDINATE instead cost a run: (10, 60) is measured
    // from the dialog's top-left, which by then is the first result row, so the
    // click opened the article and closed the palette exactly as designed, and
    // the assertion read that as the dialog failing to stay open.
    await palette.locator('i.fa-magnifying-glass').click();
    await expect(palette).toBeVisible();

    // --- keyboard navigation ------------------------------------------------
    await input.press('ArrowDown');
    await expect(hit).toHaveAttribute('aria-selected', 'true');
    await input.press('Enter');
    await expect(page).toHaveURL(new RegExp(`/article/${articleId}$`));
    await expect(page.locator('div[role="dialog"]')).toHaveCount(0);

    // --- failure and retry --------------------------------------------------
    // The retry button exists for exactly one situation, and the only way to
    // reach it is to make the search fail. Its docstring says the debounce
    // deliberately has no distinctUntilChanged so that retry can re-issue the
    // identical query -- which is precisely what this checks.
    await page.route('**/api/search/global**', (route) => route.abort());
    await page.goto('/');
    await page.getByRole('button', { name: 'ძებნა', exact: true }).click();
    const failing = page.locator('div[role="dialog"]');
    await failing.getByPlaceholder('მოძებნე სტატია, სიახლე ან ვიდეო…').fill(title);
    await expect(failing.getByText('ძებნა ვერ შესრულდა')).toBeVisible();

    await page.unroute('**/api/search/global**');
    await failing.getByRole('button', { name: 'თავიდან ცდა' }).click();
    await expect(failing.getByRole('option', { name: new RegExp(title) })).toBeVisible();
  });

  test('favourite star: toggles, persists, and does not open the card', async ({ page, request }) => {
    test.setTimeout(120_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    const auth = { Authorization: `Bearer ${token}` };
    const category = await createCategory(request, token, `E2E რჩეულის კატეგორია ${id}`);
    const title = `E2E რჩეული ${id}`;
    const articleId = await createArticle(request, token, {
      title,
      categoryId: category.id,
      targetDepartments: ['საინფორმაციო']
    });

    await seedTokenIntoPage(page, token);
    await page.goto('/info');

    // Narrowed by the page's own search first: every other spec in this run
    // also creates articles, and the card grid is not ordered around this
    // test. Without it a green result would only mean the article happened
    // to land on the first screen.
    await page.getByPlaceholder('ძიება თემით ...').fill(title);

    const card = page.locator('app-article-card', { hasText: title });
    await expect(card).toHaveCount(1);
    const star = card.locator('app-favorite-star button');
    await expect(star).toHaveAttribute('aria-pressed', 'false');

    await star.click();
    await expect(star).toHaveAttribute('aria-pressed', 'true');

    // The star sits ON the card, and the card navigates. Its stopPropagation
    // is the only thing keeping a favourite from also being a navigation --
    // so staying put is part of the assertion, not an afterthought.
    //
    // Matched on the path, not the whole URL: the knowledge base keeps its
    // search term in the query string now, so the exact-match pattern failed
    // on a page that had not navigated anywhere.
    await expect(page).toHaveURL(/\/info(\?.*)?$/);

    const favorites = await request.get('/api/favorites', { headers: auth });
    expect(
      (await favorites.json()).some(
        (f: { item_type: string; item_id: number }) => f.item_type === 'article' && f.item_id === articleId
      ),
      'the star filled in but nothing was stored'
    ).toBe(true);

    await star.click();
    await expect(star).toHaveAttribute('aria-pressed', 'false');
    const afterRemove = await request.get('/api/favorites', { headers: auth });
    expect(
      (await afterRemove.json()).some((f: { item_id: number }) => f.item_id === articleId),
      'un-starring left the favourite behind'
    ).toBe(false);

    // --- the card itself ----------------------------------------------------
    // Article routes carry a returnUrl now, so the assertion is on the path
    // rather than on the whole URL -- same reason as the /info check above.
    await card.click();
    await expect(page).toHaveURL(new RegExp(`/article/${articleId}(\\?.*)?$`));
  });

  test('quiz builder: build a quiz through the UI and read it back', async ({ page, request }) => {
    test.setTimeout(120_000);
    const id = runId();
    const token = await apiLogin(request, 'content@magti.ge');
    const auth = { Authorization: `Bearer ${token}` };
    const category = await createCategory(request, token, `E2E ქვიზის კატეგორია ${id}`);

    await seedTokenIntoPage(page, token);
    await page.goto('/admin/content');
    await page.getByRole('button', { name: 'სტატია', exact: true }).click();

    const drawer = page.locator('app-article-edit-drawer');
    const title = `E2E ქვიზის კონსტრუქტორი ${id}`;
    await drawer.locator('input[type="text"]').first().fill(title);
    await drawer.locator('select:not([class*="ql-"])').first().selectOption({ label: category.name });
    await drawer.locator('label', { hasText: 'საინფორმაციო' }).locator('input[type="checkbox"]').check();

    // The builder does not exist until the quiz toggle asks for it.
    const builder = drawer.locator('app-quiz-builder');
    await expect(builder).toHaveCount(0);
    await drawer.locator('input[type="checkbox"]').nth(1).check();
    await expect(builder).toBeVisible();

    // --- add, then throw one away -------------------------------------------
    await builder.getByRole('button', { name: 'კითხვის დამატება' }).click();
    await builder.getByRole('button', { name: 'კითხვის დამატება' }).click();
    await expect(builder.locator('textarea')).toHaveCount(2);
    // Removing the second must leave the first, not clear the lot.
    await builder.getByRole('button', { name: 'კითხვის წაშლა' }).nth(1).click();
    await expect(builder.locator('textarea')).toHaveCount(1);

    await builder.locator('textarea').fill(`რომელია სწორი? ${id}`);

    // A new question starts with two answer slots; a third is added and then
    // removed again, so both answer controls are exercised on real state.
    await expect(builder.locator('input[type="text"]')).toHaveCount(2);
    await builder.getByRole('button', { name: 'პასუხის დამატება' }).click();
    await expect(builder.locator('input[type="text"]')).toHaveCount(3);
    await builder.getByRole('button', { name: 'პასუხის წაშლა' }).nth(2).click();
    await expect(builder.locator('input[type="text"]')).toHaveCount(2);

    const wrong = `არასწორი ${id}`;
    const right = `სწორი ${id}`;
    await builder.locator('input[type="text"]').nth(0).fill(wrong);
    await builder.locator('input[type="text"]').nth(1).fill(right);

    // The default correct answer is the first one, so moving it to the second
    // is what proves the radio reaches the payload rather than the default
    // happening to match.
    await builder.locator('input[type="radio"]').nth(1).check();

    await drawer.getByRole('button', { name: 'შენახვა' }).click();
    await expect(drawer).toHaveCount(0);

    // --- the only question that matters -------------------------------------
    const list = await request.get('/api/articles', { headers: auth });
    const saved = (await list.json()).find((a: { title: string }) => a.title === title);
    expect(saved, 'the article with the quiz is not in the admin list').toBeTruthy();

    const quiz = await request.get(`/api/articles/${saved.id}/quiz/admin`, { headers: auth });
    expect(quiz.ok(), `quiz admin fetch failed: ${quiz.status()}`).toBeTruthy();
    const questions = (await quiz.json()).questions;
    expect(questions.length, 'the builder saved no questions').toBe(1);
    expect(questions[0].question_text).toBe(`რომელია სწორი? ${id}`);
    expect(questions[0].answers.map((a: { answer_text: string }) => a.answer_text)).toEqual([wrong, right]);
    const correct = questions[0].answers.filter((a: { is_correct: boolean }) => a.is_correct);
    expect(correct.length, 'exactly one answer must be correct').toBe(1);
    expect(correct[0].answer_text, 'the radio did not reach the payload').toBe(right);
  });
});
