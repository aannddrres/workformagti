import { test, expect, APIRequestContext } from '@playwright/test';
import { apiLogin, createArticle, createCategory, runId, seedTokenIntoPage } from './helpers';

/**
 * The pages an operator actually spends the day in: the news list, the
 * knowledge base, and their own favourites.
 *
 * Every control on these three screens was clicked by the sweep and asserted
 * by nobody -- they are read-only list pages, so a broken filter looks exactly
 * like a filter with nothing to show. That is the failure mode these tests are
 * built around: each filter is driven with something it MUST exclude present
 * on the page first, so "the list is now shorter" cannot pass by accident.
 */
const NEWS_PAGE_SIZE = 10; // news-page.ts:13

async function createNews(
  request: APIRequestContext,
  token: string,
  title: string,
  department: string
): Promise<number> {
  const res = await request.post('/api/news', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      title,
      content: `<p>${title}</p>`,
      target_department: department,
      attachment_url: null,
      visible_to_tech_info: true,
      visible_to_service_center: true,
      expires_at: null,
      // Explicit, and not incidental to this fixture: the create endpoint
      // defaults is_draft to false while Python's schema defaulted it to true
      // (NewsRequest.java:29-34). A fixture that relied on the default would
      // be asserting that quirk rather than the page.
      is_draft: false
    }
  });
  expect(res.ok(), `create news failed: ${res.status()} ${await res.text()}`).toBeTruthy();
  return (await res.json()).id as number;
}

test.describe('operator browsing', () => {
  test('news: search, department, sort, favourites-only, paging and refresh', async ({
    page,
    request
  }) => {
    test.setTimeout(180_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    const auth = { Authorization: `Bearer ${token}` };

    // One more than a page, so "load more" has somewhere to go, plus two
    // named items the filters can be pointed at.
    //
    // The two named items are created LAST, and that ordering is load-bearing.
    // The page fetches a page at a time and every filter on it -- search,
    // department, favourites -- runs over what has been LOADED, not over the
    // whole set (news-page.ts:53-70, a computed over allItems()). So an item
    // that has fallen off page one is invisible to the search box until
    // "load more" is pressed. Creating the named items last makes them the
    // newest, which keeps them on page one after refresh() resets to skip=0.
    //
    // Worth stating rather than just working around: an operator who searches
    // for an older item without paging first is told there is nothing.
    const techTitle = `E2E ტექნიკური სიახლე ${id}`;
    const infoTitle = `E2E საინფო სიახლე ${id}`;
    for (let i = 0; i < NEWS_PAGE_SIZE; i++) {
      await createNews(request, token, `E2E ფონური სიახლე ${id}-${i}`, 'All');
    }
    await createNews(request, token, techTitle, 'ტექნიკური');
    await createNews(request, token, infoTitle, 'საინფორმაციო');

    await seedTokenIntoPage(page, token);
    await page.goto('/news');
    await expect(page.getByRole('heading', { name: 'სიახლეები' })).toBeVisible();

    // Narrowed by the star each row carries, not by role alone: app-article-card
    // is also a div[role="button"], and a bare role match would start counting
    // article cards the day anything puts one on this page.
    const rows = page.locator('div[role="button"]:has(app-favorite-star)');
    const techRow = rows.filter({ hasText: techTitle });
    const infoRow = rows.filter({ hasText: infoTitle });

    // --- paging ------------------------------------------------------------
    // The list starts at one page; the fixture guarantees there is more.
    await expect(rows).toHaveCount(NEWS_PAGE_SIZE);
    const loadMore = page.getByRole('button', { name: 'მეტის ნახვა' });
    await expect(loadMore).toBeVisible();
    await loadMore.click();
    await expect.poll(() => rows.count()).toBeGreaterThan(NEWS_PAGE_SIZE);

    // --- search ------------------------------------------------------------
    const search = page.getByPlaceholder('ძიება სათაურით ან შინაარსით ...');
    await search.fill(techTitle);
    await expect(techRow).toHaveCount(1);
    // The point of the assertion: the OTHER named item is gone. A count of one
    // would also be true of a page that happened to render one row.
    await expect(infoRow).toHaveCount(0);

    await search.fill('');
    await expect(infoRow).toHaveCount(1);

    // --- department --------------------------------------------------------
    // Two selects on this page and no labels, so they are told apart by their
    // options rather than by position.
    const deptSelect = page.locator('select').filter({ hasText: 'ყველა დეპარტამენტი' });
    await deptSelect.selectOption('ტექნიკური');
    await expect(techRow).toHaveCount(1);
    await expect(infoRow).toHaveCount(0);

    await deptSelect.selectOption('საინფორმაციო');
    await expect(infoRow).toHaveCount(1);
    await expect(techRow).toHaveCount(0);

    await deptSelect.selectOption('');
    await expect(techRow).toHaveCount(1);

    // --- sort --------------------------------------------------------------
    // Asserted as an ORDER, not as "the control accepted a value": alphabetical
    // has one correct answer and newest-first has another, so the two titles
    // swapping places is the only thing that proves the sort ran.
    const sortSelect = page.locator('select').filter({ hasText: 'ანბანით (ა-ჰ)' });
    const titlesOf = async () =>
      (await page.locator('div[role="button"] h4').allInnerTexts()).filter((t) =>
        t.includes(id)
      );

    await sortSelect.selectOption('alphabetical');
    const alphabetical = await titlesOf();
    expect(alphabetical.length, 'the fixture items are not on the page').toBeGreaterThan(1);
    expect(
      [...alphabetical].sort((a, b) => a.localeCompare(b, 'ka')),
      'alphabetical did not order the list alphabetically'
    ).toEqual(alphabetical);

    await sortSelect.selectOption('oldest');
    // Oldest-first must be the reverse of newest-first for a fixture created in
    // a known order, so this checks the two settings actually differ.
    const oldest = await titlesOf();
    await sortSelect.selectOption('newest');
    const newest = await titlesOf();
    expect(oldest, 'oldest and newest produced the same order').not.toEqual(newest);

    // --- favourites-only ---------------------------------------------------
    // Nothing is starred yet, so the toggle must empty the list; then one item
    // is starred and it must be the only survivor.
    const favToggle = page.getByRole('button', { name: 'მხოლოდ რჩეულები' });
    await expect(favToggle).toHaveAttribute('aria-pressed', 'false');
    await favToggle.click();
    await expect(favToggle).toHaveAttribute('aria-pressed', 'true');
    await expect(techRow).toHaveCount(0);

    await favToggle.click();
    await techRow.locator('app-favorite-star button').click();
    await expect(techRow.locator('app-favorite-star button')).toHaveAttribute(
      'aria-pressed',
      'true'
    );
    await favToggle.click();
    await expect(techRow).toHaveCount(1);
    await expect(infoRow).toHaveCount(0);
    await favToggle.click();

    // --- refresh -----------------------------------------------------------
    const [refreshed] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/news')),
      page.getByRole('button', { name: 'განახლება' }).click()
    ]);
    expect(refreshed.status()).toBe(200);

    // --- open one, and come back -------------------------------------------
    await page.getByPlaceholder('ძიება სათაურით ან შინაარსით ...').fill(techTitle);
    // Visible before clicked, so a row that never arrives is reported as a
    // missing row rather than as a click that hung.
    await expect(techRow).toBeVisible();
    await techRow.click();
    await expect(page).toHaveURL(/\/news\/\d+$/);
    await expect(page.getByRole('heading', { name: techTitle })).toBeVisible();

    await page.getByRole('button', { name: 'უკან დაბრუნება' }).click();
    await expect(page).toHaveURL(/\/news$/);

    // Cleanup is not this test's job, but leaving a starred item behind would
    // change what the favourites test below sees.
    const favorites = await request.get('/api/favorites', { headers: auth });
    const mine = (await favorites.json()).find(
      (f: { item_type: string; title?: string }) => f.item_type === 'news'
    );
    expect(mine, 'the star never reached the server').toBeTruthy();
  });

  test('knowledge base: search and the category filter narrow the grid', async ({
    page,
    request
  }) => {
    test.setTimeout(120_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');

    // Two categories, one article in each: the only fixture shape that can
    // tell "the category filter works" apart from "the filter emptied the
    // page", which a single-category fixture cannot.
    const catA = await createCategory(request, token, `E2E კატეგორია A ${id}`);
    const catB = await createCategory(request, token, `E2E კატეგორია B ${id}`);
    const titleA = `E2E სტატია ალფა ${id}`;
    const titleB = `E2E სტატია ბეტა ${id}`;
    await createArticle(request, token, {
      title: titleA,
      categoryId: catA.id,
      targetDepartments: ['საინფორმაციო']
    });
    await createArticle(request, token, {
      title: titleB,
      categoryId: catB.id,
      targetDepartments: ['საინფორმაციო']
    });

    await seedTokenIntoPage(page, token);
    await page.goto('/info');
    await expect(page.getByRole('heading', { name: 'ცოდნის ბაზა' })).toBeVisible();

    const cardA = page.locator('app-article-card', { hasText: titleA });
    const cardB = page.locator('app-article-card', { hasText: titleB });

    const search = page.getByPlaceholder('ძიება თემით ...');
    await search.fill(titleA);
    await expect(cardA).toHaveCount(1);
    await expect(cardB).toHaveCount(0);

    // Widened back to this run's own two articles rather than to everything.
    // `search.fill('')` used to work and now depends on how much content the
    // shared instance has accumulated: the grid is paged, so on a database
    // with a few hundred articles the card this line is looking for is simply
    // not on the first page. Searching the run marker keeps the assertion
    // ("relaxing the filter brings the hidden card back") without making it a
    // statement about how many articles exist.
    await search.fill(id);
    await expect(cardA).toHaveCount(1);
    await expect(cardB).toHaveCount(1);

    const categorySelect = page.locator('select').filter({ hasText: 'ყველა კატეგორია' });
    await categorySelect.selectOption({ label: catA.name });
    await expect(cardA).toHaveCount(1);
    await expect(cardB).toHaveCount(0);

    await categorySelect.selectOption({ label: catB.name });
    await expect(cardB).toHaveCount(1);
    await expect(cardA).toHaveCount(0);

    // Search and category are separate filters and must compose: a term from
    // category A while category B is selected can match nothing.
    await search.fill(titleA);
    await expect(page.getByText('შედეგი ვერ მოიძებნა.')).toBeVisible();
  });

  test('favourites: the page opens what it lists and removes what it drops', async ({
    page,
    request
  }) => {
    test.setTimeout(120_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    const auth = { Authorization: `Bearer ${token}` };
    const category = await createCategory(request, token, `E2E რჩეულების კატეგორია ${id}`);
    const title = `E2E რჩეული სტატია ${id}`;
    const articleId = await createArticle(request, token, {
      title,
      categoryId: category.id,
      targetDepartments: ['საინფორმაციო']
    });

    const starred = await request.post('/api/favorites', {
      headers: auth,
      data: { item_type: 'article', item_id: articleId }
    });
    expect(starred.ok(), `seed favourite failed: ${starred.status()}`).toBeTruthy();

    await seedTokenIntoPage(page, token);
    await page.goto('/favorites');
    await expect(page.getByRole('heading', { name: 'ჩემი რჩეულები' })).toBeVisible();

    const row = page.locator('div[role="button"]', { hasText: title });
    await expect(row).toHaveCount(1);

    // --- remove ------------------------------------------------------------
    // Its stopPropagation is the whole reason this button can exist on a row
    // that navigates, so the URL not changing is half the assertion.
    await row.getByRole('button', { name: 'წაშლა' }).click();
    await expect(page.locator('div[role="button"]', { hasText: title })).toHaveCount(0);
    await expect(page).toHaveURL(/\/favorites$/);

    const after = await request.get('/api/favorites', { headers: auth });
    expect(
      (await after.json()).some((f: { item_id: number }) => f.item_id === articleId),
      'the row left the screen but the favourite is still stored'
    ).toBe(false);

    // --- open --------------------------------------------------------------
    await request.post('/api/favorites', {
      headers: auth,
      data: { item_type: 'article', item_id: articleId }
    });
    await page.reload();
    await page.locator('div[role="button"]', { hasText: title }).click();
    await expect(page).toHaveURL(new RegExp(`/article/${articleId}$`));
  });
});
