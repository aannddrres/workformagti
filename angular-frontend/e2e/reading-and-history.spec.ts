import { test, expect } from '@playwright/test';
import {
  apiLogin,
  createArticle,
  createCategory,
  loginAsUi,
  markRead,
  runId,
  seedTokenIntoPage,
  syncRequiredReading,
  updateArticle
} from './helpers';

/**
 * The compliance side an operator actually touches -- the mandatory-reading
 * list and its filters, the confirm panel's failure path -- and the version
 * history an editor opens from an article.
 *
 * NOT here, because it is already covered: the quiz gate itself. quiz-gate.
 * spec.ts drives selectAnswer and submit end to end (answer clicked, quiz
 * submitted, mark-read unlocked). Duplicating it would add runtime and no
 * evidence.
 */
const WEEK_AHEAD = () => new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString();

test.describe('reading and version history', () => {
  test('required readings: the three filters, checked from both sides', async ({ page, request }) => {
    test.setTimeout(120_000);
    const id = runId();
    const adminToken = await apiLogin(request, 'admin@magti.ge');
    const category = await createCategory(request, adminToken, `E2E წაკითხვის კატეგორია ${id}`);

    // Two obligations, one of which will be cleared. One alone cannot tell a
    // working filter from a filter that empties the page: with a single item,
    // "unread shows it" and "read shows nothing" are both satisfied by a
    // filter that simply hides everything on one setting.
    const unreadTitle = `E2E წასაკითხი ${id}`;
    const readTitle = `E2E წაკითხული ${id}`;
    const unreadId = await createArticle(request, adminToken, {
      title: unreadTitle,
      categoryId: category.id,
      targetDepartments: ['Support']
    });
    const readId = await createArticle(request, adminToken, {
      title: readTitle,
      categoryId: category.id,
      targetDepartments: ['Support']
    });
    await syncRequiredReading(request, adminToken, unreadId, 'Support', WEEK_AHEAD());
    const readReadingId = await syncRequiredReading(request, adminToken, readId, 'Support', WEEK_AHEAD());

    // A JIT persona, which provisions into "Support" -- the department both
    // obligations target, so no admin-side user setup is needed.
    const operatorEmail = `test_operator_read_${id}@magti.ge`;
    await loginAsUi(page, operatorEmail);

    // Cleared through the API, as the operator: mark-read is per-user, so it
    // has to be their own token, not the admin's.
    const operatorToken = await apiLogin(request, operatorEmail);
    await markRead(request, operatorToken, readReadingId);

    await page.goto('/reading');
    await expect(page.getByRole('heading', { name: 'სავალდებულო გაცნობა' })).toBeVisible();

    const unreadRow = page.getByRole('link', { name: unreadTitle });
    const readRow = page.getByRole('link', { name: readTitle });

    // --- all ---------------------------------------------------------------
    await page.getByRole('button', { name: 'ყველა', exact: true }).click();
    await expect(unreadRow).toHaveCount(1);
    await expect(readRow).toHaveCount(1);

    // --- unread ------------------------------------------------------------
    // exact: true throughout. "წასაკითხი" is both a filter button and the
    // status badge on a row, and the read filter's label "წაკითხული" contains
    // neither -- but the badge text "წაკითხულია" does contain it.
    await page.getByRole('button', { name: 'წასაკითხი', exact: true }).click();
    await expect(unreadRow).toHaveCount(1);
    await expect(readRow).toHaveCount(0);

    // --- read --------------------------------------------------------------
    await page.getByRole('button', { name: 'წაკითხული', exact: true }).click();
    await expect(readRow).toHaveCount(1);
    await expect(unreadRow).toHaveCount(0);

    await page.getByRole('button', { name: 'ყველა', exact: true }).click();
    await expect(unreadRow).toHaveCount(1);
  });

  test('the confirm panel says when it could not look the obligation up, and recovers', async ({
    page,
    request
  }) => {
    test.setTimeout(120_000);
    const id = runId();
    const adminToken = await apiLogin(request, 'admin@magti.ge');
    const category = await createCategory(request, adminToken, `E2E დადასტურების კატეგორია ${id}`);
    const articleId = await createArticle(request, adminToken, {
      title: `E2E დასადასტურებელი ${id}`,
      categoryId: category.id,
      targetDepartments: ['Support']
    });
    await syncRequiredReading(request, adminToken, articleId, 'Support', WEEK_AHEAD());

    await loginAsUi(page, `test_operator_conf_${id}@magti.ge`);

    // The retry button exists for exactly one situation and the only way to
    // reach it is to make the lookup fail. Worth covering rather than
    // skipping: this panel decides whether an operator is shown a compliance
    // obligation at all, and a lookup that fails silently would simply not
    // show it -- indistinguishable, on screen, from having none.
    await page.route('**/api/compliance/my-readings**', (route) => route.abort());
    await page.goto(`/article/${articleId}`);

    const confirm = page.locator('app-reading-confirm');
    await expect(
      confirm.getByText('ვერ დადგინდა, სავალდებულოა თუ არა ეს მასალა შენთვის.')
    ).toBeVisible();
    // The confirm action must NOT be offered while the lookup is unknown.
    await expect(confirm.getByRole('button', { name: 'წავიკითხე' })).toHaveCount(0);

    await page.unroute('**/api/compliance/my-readings**');
    await confirm.getByRole('button', { name: 'თავიდან ცდა' }).click();

    await expect(confirm.getByRole('button', { name: 'წავიკითხე' })).toBeVisible();
    await expect(
      confirm.getByText('ვერ დადგინდა, სავალდებულოა თუ არა ეს მასალა შენთვის.')
    ).toHaveCount(0);
  });

  test('version history: open it, pick a version, compare against another', async ({
    page,
    request
  }) => {
    test.setTimeout(120_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    const category = await createCategory(request, token, `E2E ისტორიის კატეგორია ${id}`);
    const title = `E2E ისტორიის სტატია ${id}`;
    const articleId = await createArticle(request, token, {
      title,
      categoryId: category.id,
      targetDepartments: ['საინფორმაციო'],
      content: '<p>პირველი რედაქცია.</p>'
    });

    // Four versions. The compare select only appears when there is something
    // OTHER than the selected version to compare against, and it defaults to
    // the immediate predecessor -- so a fixture with only two versions leaves
    // one option, already selected, and choosing it fires no change event at
    // all. A full-body PUT is what writes an article_history row; there is no
    // other way to produce this fixture.
    for (const text of ['მეორე რედაქცია.', 'მესამე რედაქცია.', 'მეოთხე რედაქცია.']) {
      await updateArticle(request, token, articleId, {
        title,
        categoryId: category.id,
        targetDepartments: ['საინფორმაციო'],
        content: `<p>${text}</p>`
      });
    }

    await seedTokenIntoPage(page, token);
    await page.goto(`/article/${articleId}`);

    // --- open --------------------------------------------------------------
    const overlay = page.locator('app-article-version-history-overlay');
    await expect(overlay).toHaveCount(0);

    // /versions, not /history. There are two version endpoints and the reader
    // overlay uses the first (articles.service.ts:131); /history is the admin
    // one. Waiting on the wrong one sat for 15s on a request that was never
    // going to be made.
    const [listed] = await Promise.all([
      page.waitForResponse((r) => /\/api\/articles\/\d+\/versions$/.test(r.url())),
      page.getByRole('button', { name: 'ვერსიების ისტორია' }).click()
    ]);
    expect(listed.status(), 'opening the overlay must fetch the version list').toBe(200);
    await expect(overlay.getByText('ცვლილებების ისტორია')).toBeVisible();

    // --- pick a version ----------------------------------------------------
    // The SECOND row, not the first, and by the row's own class rather than
    // by its badge text.
    //
    // load() auto-selects data[0] as soon as the version list arrives and
    // fetches its diff (overlay ts:76-78), so clicking the top row asks for a
    // diff that is already on screen -- nothing observable changes and there
    // is no clean signal to wait on. The second row is an actual state
    // change. `cursor-pointer` is the row's own base class (ROW_BASE, ts:88),
    // which is what makes this a stable handle on an otherwise unlabelled div.
    // Not an exact count: whether the list carries the current state as its
    // own entry or only the saved history rows is the component's business,
    // and this test does not need to pin it down. More than one is what the
    // rest of the assertions actually depend on.
    const rows = overlay.getByRole('button', { name: /^V\d/ });
    await expect(rows.nth(1)).toBeVisible();

    const [selected] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/diff')),
      rows.nth(1).click()
    ]);
    expect(selected.status(), 'selecting a version must fetch its diff').toBe(200);

    // --- compare against a different version -------------------------------
    // The select defaults to the predecessor, so picking the LAST option is
    // what guarantees a real change event rather than a no-op re-selection.
    const compare = overlay.getByRole('combobox', { name: 'შედარება:' });
    await expect(compare).toBeVisible();
    const optionCount = await compare.locator('option').count();
    expect(optionCount, 'the fixture did not produce enough versions to compare').toBeGreaterThan(1);

    const [diffed] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/diff')),
      compare.selectOption({ index: optionCount - 1 })
    ]);
    expect(diffed.status(), 'the comparison must reach the server').toBe(200);
    await expect(overlay.getByText('დამატებული')).toBeVisible();
    await expect(overlay.getByText('წაშლილი')).toBeVisible();

    // --- close, then leave the article -------------------------------------
    await overlay.getByRole('button', { name: 'დახურვა' }).click();
    await expect(overlay).toHaveCount(0);

    await page.getByRole('button', { name: 'უკან დაბრუნება' }).click();
    await expect(page).not.toHaveURL(new RegExp(`/article/${articleId}$`));
  });
});
