import { test, expect } from '@playwright/test';
import { apiLogin, runId, seedTokenIntoPage } from './helpers';

/**
 * Direct messages and the admin broadcast, both followed all the way to the
 * recipient's inbox.
 *
 * A "sent" confirmation on the sender's screen is the weakest possible
 * evidence for a messaging feature -- it is exactly what a message that went
 * nowhere also looks like. So each test logs in as the recipient afterwards
 * and checks the message arrived, then reads it and deletes it from there.
 * The delete control only exists on inbox rows (messaging-page.ts:25-26),
 * which is why the recipient is the one who has to run it.
 */
const RECIPIENT_PASSWORD = 'E2Epass123!';

test.describe('messaging', () => {
  test('compose: pick a recipient, send, and read it as them', async ({ page, request }) => {
    test.setTimeout(120_000);
    const id = runId();
    const adminToken = await apiLogin(request, 'admin@magti.ge');
    const auth = { Authorization: `Bearer ${adminToken}` };

    // A real account rather than a JIT persona, because the broadcast test
    // below needs a recipient whose department is one the broadcast form can
    // actually target -- the JIT personas land in "Support", which is not in
    // DEPARTMENTS (user-roles.ts:3).
    const email = `e2e_msg_${id}@magti.ge`;
    const name = `E2E მიმღები ${id}`;
    const created = await request.post('/api/users', {
      headers: auth,
      data: {
        email,
        name,
        department: 'ტექნიკური',
        position: null,
        phone: null,
        role: 'operator',
        password: RECIPIENT_PASSWORD,
        team_id: null
      }
    });
    expect(created.ok(), `seed recipient failed: ${created.status()} ${await created.text()}`).toBeTruthy();

    await seedTokenIntoPage(page, adminToken);
    await page.goto('/profile/messages');

    await page.getByRole('button', { name: 'ახალი შეტყობინება' }).click();
    const modal = page.locator('app-compose-message-modal');
    await expect(modal.getByText('ახალი შეტყობინება').first()).toBeVisible();

    // --- the recipient picker ---------------------------------------------
    const search = modal.getByPlaceholder('ძიება სახელით ან დეპარტამენტით...');
    await search.fill(name);
    await modal.getByRole('button', { name }).click();
    // Selecting replaces the search box with a chip naming the choice.
    await expect(search).toHaveCount(0);
    await expect(modal.getByText(name)).toBeVisible();

    // "Change" has to give the search back, or a mis-picked recipient would
    // be unrecoverable without closing the whole modal.
    await modal.getByRole('button', { name: 'შეცვლა' }).click();
    await expect(modal.getByPlaceholder('ძიება სახელით ან დეპარტამენტით...')).toBeVisible();

    await modal.getByPlaceholder('ძიება სახელით ან დეპარტამენტით...').fill(name);
    await modal.getByRole('button', { name }).click();

    const body = `E2E პირადი შეტყობინება ${id}`;
    await modal.getByPlaceholder('დაწერეთ შეტყობინება...').fill(body);
    await modal.getByRole('button', { name: 'გაგზავნა' }).click();
    await expect(modal).toHaveCount(0);

    // --- the sender's own record ------------------------------------------
    await page.getByRole('button', { name: 'გაგზავნილი' }).click();
    await expect(page.getByText(body)).toBeVisible();

    // --- and now the only thing that matters ------------------------------
    const recipientToken = await apiLogin(request, email, RECIPIENT_PASSWORD);
    await seedTokenIntoPage(page, recipientToken);
    await page.goto('/profile/messages');

    const row = page.locator('div[role="button"]', { hasText: body });
    await expect(row).toHaveCount(1);
    await expect(row.getByText('წაუკითხავი')).toBeVisible();

    // Opening it marks it read -- the badge going away is the whole visible
    // consequence.
    await row.click();
    await expect(row.getByText('წაუკითხავი')).toHaveCount(0);

    // --- delete, from the only place it is offered -------------------------
    page.once('dialog', (dialog) => dialog.accept());
    await row.getByRole('button').click();
    await expect(page.locator('div[role="button"]', { hasText: body })).toHaveCount(0);

    const inbox = await request.get('/api/messages', {
      headers: { Authorization: `Bearer ${recipientToken}` }
    });
    expect(
      (await inbox.json()).some((m: { content: string }) => m.content === body),
      'the row left the screen but the message is still in the inbox'
    ).toBe(false);
  });

  test('broadcast: department and role narrow who receives it', async ({ page, request }) => {
    test.setTimeout(120_000);
    const id = runId();
    const adminToken = await apiLogin(request, 'admin@magti.ge');
    const auth = { Authorization: `Bearer ${adminToken}` };

    const email = `e2e_bcast_${id}@magti.ge`;
    const created = await request.post('/api/users', {
      headers: auth,
      data: {
        email,
        name: `E2E მიმღები ${id}`,
        department: 'ტექნიკური',
        position: null,
        phone: null,
        role: 'operator',
        password: RECIPIENT_PASSWORD,
        team_id: null
      }
    });
    expect(created.ok(), `seed recipient failed: ${created.status()}`).toBeTruthy();

    await seedTokenIntoPage(page, adminToken);
    await page.goto('/profile/messages');
    await page.getByRole('button', { name: 'მასობრივი შეტყობინება' }).click();

    const modal = page.locator('app-broadcast-modal');
    const body = `E2E მასობრივი ${id}`;
    await modal.locator('select').first().selectOption('ტექნიკური');
    await modal.locator('select').nth(1).selectOption('operator');
    await modal.getByPlaceholder('დაწერეთ განცხადება...').fill(body);
    await modal.getByRole('button', { name: 'გაგზავნა' }).click();

    // The form reports how many people it reached; anything less than one
    // means the department and role narrowed it down to nobody.
    await expect(modal.getByText(/გაიგზავნა \d+ მიმღებთან/)).toBeVisible();
    const reported = await modal.getByText(/გაიგზავნა \d+ მიმღებთან/).innerText();
    expect(Number(reported.match(/\d+/)![0]), 'the broadcast reached nobody').toBeGreaterThan(0);

    // Reported is not received. Check the inbox of somebody the filter
    // should have included.
    const recipientToken = await apiLogin(request, email, RECIPIENT_PASSWORD);
    const inbox = await request.get('/api/messages', {
      headers: { Authorization: `Bearer ${recipientToken}` }
    });
    expect(
      (await inbox.json()).some((m: { content: string }) => m.content.includes(body)),
      'the broadcast was reported as sent but never arrived'
    ).toBe(true);
  });
});
