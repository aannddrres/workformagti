import { test, expect, APIRequestContext } from '@playwright/test';
import { apiLogin, createArticle, createCategory, runId, seedTokenIntoPage } from './helpers';

/**
 * The frame every page sits inside -- language, the mobile menu, logout --
 * plus the admin dashboard's progress controls and the video list.
 *
 * The shell is the highest-traffic code in the product by definition: it is
 * on screen for every interaction anyone has. It was also the last thing
 * without a single assertion on it.
 */
async function createVideo(
  request: APIRequestContext,
  token: string,
  title: string
): Promise<number> {
  const res = await request.post('/api/videos', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      title,
      video_url: 'https://youtu.be/dQw4w9WgXcQ',
      category: 'E2E',
      target_department: 'All',
      tags: null
    }
  });
  expect(res.ok(), `create video failed: ${res.status()} ${await res.text()}`).toBeTruthy();
  return (await res.json()).id as number;
}

test.describe('the shell', () => {
  test('Georgian-only shell keeps the selected accessible font size across navigation', async ({
    page,
    request
  }) => {
    test.setTimeout(120_000);
    const token = await apiLogin(request, 'admin@magti.ge');
    await seedTokenIntoPage(page, token);
    await page.goto('/');

    await expect(page.getByRole('button', { name: 'EN', exact: true })).toHaveCount(0);
    const fontMenu = page.getByRole('button', { name: 'შრიფტის ზომა' });
    await fontMenu.click();
    await page.getByRole('button', { name: '130%', exact: true }).click();
    await expect(fontMenu).toContainText('130%');
    await expect.poll(() => page.evaluate(() => document.documentElement.dataset['fontScale'])).toBe('130');

    await page.getByRole('link', { name: 'სიახლეები' }).click();
    await expect(page).toHaveURL(/\/news$/);
    await expect(fontMenu).toContainText('130%');
    await page.reload();
    await expect(fontMenu).toContainText('130%');
    await fontMenu.click();
    await page.getByRole('button', { name: '100%', exact: true }).click();
  });

  test('the mobile menu opens, closes from its backdrop, and is not there on a desktop', async ({
    page,
    request
  }) => {
    test.setTimeout(120_000);
    const token = await apiLogin(request, 'admin@magti.ge');
    await seedTokenIntoPage(page, token);

    // md:hidden on the trigger and md:static on the sidebar -- below the
    // breakpoint is the only width at which this control exists at all.
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/');

    const menuToggle = page.getByRole('button', { name: 'მენიუს გახსნა' });
    await expect(menuToggle).toBeVisible();

    const backdrop = page.getByRole('button', { name: 'მენიუს დახურვა' });
    await expect(backdrop).toHaveCount(0);

    await menuToggle.click();
    await expect(backdrop).toBeVisible();
    await expect(page.getByRole('link', { name: 'სიახლეები' })).toBeVisible();

    // Closed from the backdrop, which is the only thing closeMobileMenu is
    // wired to (app-shell.html:4).
    //
    // x: 350, not x: 5. The open sidebar is `fixed inset-y-0 left-0 w-64`
    // at z-40, above the backdrop's z-30, so a click near the left edge lands
    // on the sidebar instead and Playwright waits out the full action timeout
    // for a backdrop that will never receive it. 350 is clear of the 256px
    // sidebar in this 390px viewport.
    await backdrop.click({ position: { x: 350, y: 400 } });
    await expect(backdrop).toHaveCount(0);

    // And the trigger disappears above the breakpoint rather than merely
    // being ignored -- the sidebar is permanent there.
    await page.setViewportSize({ width: 1280, height: 800 });
    await expect(menuToggle).toBeHidden();
  });

  test('logout clears the session and the token it was holding stops working', async ({
    page,
    request
  }) => {
    test.setTimeout(120_000);
    // A throwaway JIT persona, not a shared one. This test's whole point is
    // that logout REVOKES the token server-side (SEC-14 bumps
    // users.token_version), and apiLogin caches tokens per email -- so
    // logging out a shared persona would hand every later spec a token the
    // server has already invalidated.
    const token = await apiLogin(request, `test_operator_logout_${runId()}@magti.ge`);
    await seedTokenIntoPage(page, token);
    await page.goto('/');

    await page.getByRole('button', { name: 'ანგარიშის მენიუ' }).click();
    const [loggedOut] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/auth/logout')),
      page.getByRole('button', { name: 'სისტემიდან გასვლა' }).click()
    ]);
    expect(loggedOut.status()).toBe(200);
    await expect(page).toHaveURL(/\/login$/);

    // The local token is gone...
    expect(await page.evaluate(() => localStorage.getItem('magti_token'))).toBeNull();

    // ...and so is the server's willingness to accept it. This is SEC-14:
    // logout increments users.token_version, which revokes tokens already
    // issued. Checking only the redirect would pass on a build where logout
    // did nothing but clear localStorage, and the token would still open the
    // whole portal for anyone who had copied it.
    const afterLogout = await request.get('/api/articles', {
      headers: { Authorization: `Bearer ${token}` }
    });
    expect(
      afterLogout.status(),
      'the token logout was meant to revoke still works'
    ).toBe(401);
  });
});

test.describe('admin dashboard and videos', () => {
  test('the compliance-progress controls narrow and reorder the table', async ({ page, request }) => {
    test.setTimeout(120_000);
    const token = await apiLogin(request, 'admin@magti.ge');
    await seedTokenIntoPage(page, token);
    await page.goto('/admin/overview');
    await expect(page.getByRole('heading', { name: 'სისტემის მიმოხილვა' })).toBeVisible();

    // --- refresh -----------------------------------------------------------
    const [refreshed] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/statistics/')),
      page.getByRole('button', { name: 'მონაცემების განახლება' }).click()
    ]);
    expect(refreshed.status()).toBe(200);

    // --- the progress table's own three controls ---------------------------
    // Told apart by their options rather than by position: there are several
    // selects on this page and none of them carry a label element.
    const deptSelect = page.locator('select').filter({ hasText: 'ყველა დეპარტამენტი' });
    const sortSelect = page.locator('select').filter({ hasText: 'შესრულება: მაღალი' });
    await expect(deptSelect).toBeVisible();
    await expect(sortSelect).toBeVisible();

    // The table is built from whatever users exist on this shared instance,
    // so the assertion is on ORDER changing, not on particular names: sorting
    // by name and by performance cannot both be the same sequence unless the
    // control did nothing.
    // The progress table identified by its own first column header, not by
    // position among the page's tables. `.last()` would have been a guess, and
    // guessed selectors have cost three CI cycles on this branch already.
    const progressTable = page.locator('table').filter({ hasText: 'მომხმარებელი' });
    // allInnerTexts() reads once and never retries, so it has to be given a
    // populated table to read. Changing the sort re-renders these rows, and
    // reading straight after the select caught the empty frame in between --
    // reported as "no progress rows to sort" while the page in the failure
    // screenshot plainly had eight. The wait is the fix; the row count below
    // is still the assertion.
    const namesOf = async () => {
      await expect(progressTable.locator('tbody tr').first()).toBeVisible();
      return progressTable.locator('tbody tr td:first-child').allInnerTexts();
    };

    await sortSelect.selectOption('name');
    const byName = await namesOf();
    await sortSelect.selectOption('perf_desc');
    const byPerformance = await namesOf();
    expect(byName.length, 'no progress rows to sort').toBeGreaterThan(1);
    expect(byName, 'the two sort orders produced the identical sequence').not.toEqual(byPerformance);

    // --- incomplete only ---------------------------------------------------
    // A subset, never more: whatever it shows has to be contained in the
    // unfiltered set.
    const before = (await namesOf()).length;
    await page.getByRole('button', { name: 'მხოლოდ არასრული' }).click();
    const after = (await namesOf()).length;
    expect(after, 'the filter added rows instead of removing them').toBeLessThanOrEqual(before);

    await page.getByRole('button', { name: 'მხოლოდ არასრული' }).click();
    expect((await namesOf()).length).toBe(before);

    // --- and the department select -----------------------------------------
    const options = await deptSelect.locator('option').count();
    if (options > 1) {
      await deptSelect.selectOption({ index: 1 });
      expect((await namesOf()).length).toBeLessThanOrEqual(before);
    }
  });

  test('videos: search narrows the grid, and a card opens its own video', async ({
    page,
    request
  }) => {
    test.setTimeout(120_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');

    const wanted = `E2E ვიდეო ალფა ${id}`;
    const other = `E2E ვიდეო ბეტა ${id}`;
    const wantedId = await createVideo(request, token, wanted);
    await createVideo(request, token, other);

    await seedTokenIntoPage(page, token);
    await page.goto('/videos');
    await expect(page.getByRole('heading', { name: 'ვიდეო ინსტრუქციები' })).toBeVisible();

    const wantedCard = page.locator('article', { hasText: wanted });
    const otherCard = page.locator('article', { hasText: other });
    await expect(wantedCard).toHaveCount(1);
    await expect(otherCard).toHaveCount(1);

    // The other video is the point: "one card is left" would also be true of
    // a search that emptied the grid and happened to leave a stray.
    await page.getByPlaceholder('ძიება...').fill(wanted);
    await expect(wantedCard).toHaveCount(1);
    await expect(otherCard).toHaveCount(0);

    await wantedCard.click();
    await expect(page).toHaveURL(new RegExp(`/videos/${wantedId}$`));
    await expect(page.getByRole('heading', { name: wanted })).toBeVisible();

    await page.getByRole('button', { name: 'უკან', exact: true }).click();
    await expect(page).toHaveURL(/\/videos$/);
  });

  test('a category page comes back to where it was opened from', async ({ page, request }) => {
    test.setTimeout(120_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    // The fixture title deliberately avoids the word the back button uses.
    // "უკან" inside an article title made the card's accessible name contain
    // it too, and getByRole matches names by substring -- so the locator
    // resolved to both the button and the card, and failed on strict mode.
    const category = await createCategory(request, token, `E2E ნავიგაციის კატეგორია ${id}`);
    await createArticle(request, token, {
      title: `E2E ნავიგაციის სტატია ${id}`,
      categoryId: category.id,
      targetDepartments: ['საინფორმაციო']
    });

    await seedTokenIntoPage(page, token);
    await page.goto('/info');
    await page.goto(`/category/${category.id}`);

    await page.getByRole('button', { name: 'უკან', exact: true }).click();
    await expect(page).toHaveURL(/\/info$/);
  });
});
