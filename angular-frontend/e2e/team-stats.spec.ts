import { test, expect, Page } from '@playwright/test';
import { apiLogin, runId, seedTokenIntoPage } from './helpers';

/**
 * The manager dashboard: sorting, refresh, the two drill-down modals and the
 * four report exports.
 *
 * SCOPE, stated rather than implied: the export assertions cover that the
 * server ACCEPTED the job, not that a file was produced. Three of the four
 * are asynchronous (submit -> poll -> download, team-stats-page.ts:212-230),
 * so whether the finished artefact is correct depends on a background worker
 * and, for the PDFs, on a font being present -- separate claims that deserve
 * their own test rather than being smuggled into this one.
 */
const GROUPED_DEPARTMENT = 'ტექნიკური — ჯგუფი E2E';

/** All four export buttons share one disabled condition while a job is in
 *  flight, so the next one cannot be pressed until the last has settled. */
async function waitForExportsIdle(page: Page): Promise<void> {
  await expect(page.getByRole('button', { name: 'CSV', exact: true })).toBeEnabled({ timeout: 60_000 });
}

test('manager dashboard: sort, drill-downs and the four exports', async ({ page, request }) => {
  test.setTimeout(180_000);
  const id = runId();
  const token = await apiLogin(request, 'admin@magti.ge');
  const auth = { Authorization: `Bearer ${token}` };

  // The dashboard is built from users' department strings, and a GROUP only
  // exists when that string carries the "department — group" form
  // (DepartmentMatcher.splitGroup). Two plain-department users would render
  // no group card at all and openGroupModal would be unreachable.
  for (const n of [1, 2]) {
    const res = await request.post('/api/users', {
      headers: auth,
      data: {
        email: `e2e_team_${id}_${n}@magti.ge`,
        name: `E2E გუნდი ${id}-${n}`,
        department: GROUPED_DEPARTMENT,
        position: null,
        phone: null,
        role: 'operator',
        password: 'E2Epass123!',
        team_id: null
      }
    });
    expect(res.ok(), `seed team member failed: ${res.status()} ${await res.text()}`).toBeTruthy();
  }

  await seedTokenIntoPage(page, token);
  await page.goto('/manager');
  // By ROLE, not by text: this page's name appears three times -- the sidebar
  // link that navigates here, the heading, and the "PDF (გუნდის სტატისტიკა)"
  // export button -- so a bare text match is a strict-mode violation.
  await expect(page.getByRole('heading', { name: 'გუნდის სტატისტიკა' })).toBeVisible();

  // --- sort ---------------------------------------------------------------
  // The button's own label is the state: it names what pressing it will
  // switch TO, so the label flipping is the visible proof the mode changed.
  const sortByCompliance = page.getByRole('button', { name: 'დალაგება: შესრულების მიხედვით' });
  await expect(sortByCompliance).toBeVisible();
  await sortByCompliance.click();
  await expect(page.getByRole('button', { name: 'დალაგება: სახელის მიხედვით' })).toBeVisible();
  await page.getByRole('button', { name: 'დალაგება: სახელის მიხედვით' }).click();
  await expect(sortByCompliance).toBeVisible();

  // --- refresh ------------------------------------------------------------
  const [refreshed] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/api/manager/department-stats')),
    page.getByRole('button', { name: 'განახლება' }).click()
  ]);
  expect(refreshed.status()).toBe(200);

  // --- the critical-operators modal --------------------------------------
  // Opening it fetches; the assertion is that the fetch happened and the
  // dialog rendered, since an empty result is a legitimate outcome on a
  // database where nobody is overdue yet.
  const [critical] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/api/admin/critical-operators')),
    page.locator('div[role="button"]').first().click()
  ]);
  expect(critical.status()).toBe(200);
  const criticalDialog = page.locator('div[role="dialog"]');
  await expect(criticalDialog).toBeVisible();

  // Clicking inside must NOT close it -- that is what the stopPropagation on
  // the inner panel is for, and a dialog that shuts when you click its own
  // contents is unusable.
  //
  // On its own heading, not at a coordinate. The panel is centred with
  // `p-4` padding on the backdrop and rounded corners, and a fixed offset
  // into its bounding box stopped landing on painted pixels after the
  // redesign -- Playwright reported <html> as the element intercepting the
  // click. A real child element is both stable and closer to what a user does.
  await criticalDialog.getByRole('heading').click();
  await expect(criticalDialog).toBeVisible();

  await criticalDialog.locator('button:has(.fa-xmark)').click();
  await expect(criticalDialog).toHaveCount(0);

  // --- the group modal ----------------------------------------------------
  const groupCard = page.locator('div[role="button"]', { hasText: 'ჯგუფი E2E' }).first();
  const [groupUsers] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/groups/')),
    groupCard.click()
  ]);
  expect(groupUsers.status()).toBe(200);
  const groupDialog = page.locator('div[role="dialog"]');
  await expect(groupDialog).toBeVisible();
  // The seeded members have to be in it -- otherwise the drill-down is
  // opening onto somebody else's group.
  await expect(groupDialog.getByText(`E2E გუნდი ${id}-1`)).toBeVisible();

  // Closed by the backdrop this time, so both routes out are covered.
  await page.locator('div.fixed.inset-0').first().click({ position: { x: 5, y: 5 } });
  await expect(groupDialog).toHaveCount(0);

  // --- the exports --------------------------------------------------------
  const [csv] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/api/export/readings')),
    page.getByRole('button', { name: 'CSV', exact: true }).click()
  ]);
  expect(csv.status(), 'the CSV export must reach the server').toBe(200);
  await waitForExportsIdle(page);

  for (const [label, endpoint] of [
    ['Excel (XLSX)', '/api/export/readings.xlsx'],
    ['PDF (წაკითხვები)', '/api/export/readings.pdf'],
    ['PDF (გუნდის სტატისტიკა)', '/api/export/team-stats.pdf']
  ] as const) {
    const [submitted] = await Promise.all([
      page.waitForResponse((r) => r.url().includes(endpoint)),
      page.getByRole('button', { name: label, exact: true }).click()
    ]);
    expect(submitted.status(), `${label} must be accepted by the server`).toBe(200);
    await waitForExportsIdle(page);
  }
});
