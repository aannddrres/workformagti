import { test, expect } from '@playwright/test';
import { apiLogin, openAccessTab, runId, seedTokenIntoPage } from './helpers';

/**
 * The RBAC screen: create a user, switch them off and on again, then edit
 * their role, department, position and granular permissions.
 *
 * This is the screen where a wrong value is worst -- it decides who can see
 * and do what -- and every control on it had only ever been clicked. The
 * assertions are on the saved user read back from the API, because that is
 * the record the rest of the app enforces against.
 *
 * NOT covered here, deliberately: the group filter and its retry button.
 * Group membership cannot be set from this screen, so there is no way to
 * build a fixture where filtering by a group provably excludes someone --
 * and an assertion that cannot fail is not worth writing. Left as a
 * known gap rather than a green tick.
 */
test.describe('admin users', () => {
  test('create a user, then deactivate and reactivate them', async ({ page, request }) => {
    test.setTimeout(90_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    const auth = { Authorization: `Bearer ${token}` };

    await seedTokenIntoPage(page, token);
    await openAccessTab(page, 'users');

    // The cancel button has to actually close the panel, so check that
    // first -- otherwise a stuck panel would look like a create failure.
    await page.getByRole('button', { name: 'ახალი მომხმარებელი' }).click();
    await expect(page.getByText('ახალი მომხმარებლის შექმნა')).toBeVisible();
    await page.getByRole('button', { name: 'გაუქმება' }).click();
    await expect(page.getByText('ახალი მომხმარებლის შექმნა')).toBeHidden();

    await page.getByRole('button', { name: 'ახალი მომხმარებელი' }).click();
    const form = page.locator('form');

    const email = `e2e_user_${id}@magti.ge`;
    const name = `E2E მომხმარებელი ${id}`;
    await form.locator('input[type="text"]').first().fill(name);
    await form.locator('input[type="email"]').fill(email);
    await form.locator('select').first().selectOption('ტექნიკური');
    await form.getByPlaceholder('მაგ. ოპერატორი').fill('E2E პოზიცია');
    await form.locator('select').nth(1).selectOption('manager');
    await form.getByPlaceholder('მინ. 8 სიმბოლო, ციფრი, დიდი + პატარა ასო').fill('E2Epass123!');

    await page.getByRole('button', { name: 'შენახვა' }).click();

    // Narrowed by the table's own search first. It pages at 50
    // (admin-users-page.ts:61) and this instance accumulates a handful of
    // test operators per E2E run, so a freshly created user stopped being on
    // the first page -- which reads as "the user was not created" when it is
    // really "the user is on page four".
    await page.getByPlaceholder('სახელი, ელფოსტა ან დეპარტამენტი').fill(email);

    const row = page.locator('tr', { hasText: email });
    await expect(row).toHaveCount(1);
    await expect(row.getByText('მენეჯერი')).toBeVisible();

    // --- what actually got stored -----------------------------------------
    const list = await request.get('/api/users', { headers: auth });
    expect(list.ok(), `GET /api/users failed: ${list.status()}`).toBeTruthy();
    const created = (await list.json()).find((u: { email: string }) => u.email === email);
    expect(created, 'the created user is not in /api/users').toBeTruthy();
    expect(created.name, 'the name field did not reach the payload').toBe(name);
    expect(created.department, 'the department select did not reach the payload').toBe('ტექნიკური');
    expect(created.position, 'the position field did not reach the payload').toBe('E2E პოზიცია');
    expect(created.role, 'the role select did not reach the payload').toBe('manager');
    expect(created.is_active, 'a new user starts active').toBe(true);

    // --- the status toggle -------------------------------------------------
    // State and action are two different elements now: the state is a pill,
    // and the control is an icon button whose aria-label names what clicking
    // it WILL do. Asserting the pill rather than the button is deliberate --
    // a button labelled by its action tells you nothing about what the row
    // currently is, which is the thing being verified.
    await expect(row.getByText('აქტიური', { exact: true })).toBeVisible();
    await row.getByRole('button', { name: 'მომხმარებლის გათიშვა' }).click();
    await expect(row.getByText('გათიშული', { exact: true })).toBeVisible();

    const afterOff = await request.get('/api/users', { headers: auth });
    expect(
      (await afterOff.json()).find((u: { id: number }) => u.id === created.id).is_active,
      'the label flipped but the user is still active in the database'
    ).toBe(false);

    await row.getByRole('button', { name: 'მომხმარებლის გააქტიურება' }).click();
    await expect(row.getByText('აქტიური', { exact: true })).toBeVisible();

    const afterOn = await request.get('/api/users', { headers: auth });
    expect((await afterOn.json()).find((u: { id: number }) => u.id === created.id).is_active).toBe(true);
  });

  test('edit modal: role, department, position and granular permissions', async ({ page, request }) => {
    test.setTimeout(90_000);
    const id = runId();
    const token = await apiLogin(request, 'admin@magti.ge');
    const auth = { Authorization: `Bearer ${token}` };

    // Seeded through the API: this test is about the edit modal, and driving
    // the create panel again would only re-test the previous test.
    const email = `e2e_edit_${id}@magti.ge`;
    const create = await request.post('/api/users', {
      headers: auth,
      data: {
        email,
        name: `E2E სარედაქციო ${id}`,
        department: 'ოფისი',
        position: null,
        phone: null,
        role: 'manager',
        password: 'E2Epass123!',
        team_id: null
      }
    });
    expect(create.ok(), `seed user failed: ${create.status()} ${await create.text()}`).toBeTruthy();
    const userId = (await create.json()).id as number;

    await seedTokenIntoPage(page, token);
    await openAccessTab(page, 'users');

    await page.getByPlaceholder('სახელი, ელფოსტა ან დეპარტამენტი').fill(email);

    const row = page.locator('tr', { hasText: email });
    await expect(row).toHaveCount(1);
    // By its label, not its position. The row grew a second icon button (the
    // status toggle) in the redesign and .last() silently became that one --
    // the click still succeeded, so the failure surfaced as a modal that
    // never opened rather than as a wrong click.
    await row.getByRole('button', { name: 'რედაქტირება' }).click();

    const modal = page.locator('app-user-edit-modal');
    await expect(modal.getByText('მომხმარებლის რედაქტირება')).toBeVisible();

    // Permissions are no longer a checkbox each. Phase 6/9 replaced the flat
    // list with the override model the backend actually stores: every
    // permission shows what the ROLE gives ("inherits"/"does not inherit"),
    // what the answer currently IS (the effective pill), and a three-state
    // override -- INHERIT, ALLOW, DENY. Addressed by data-permission rather
    // than by nesting, so this survives the next visual pass.
    const exportPerm = modal.locator('[data-permission="reports.export"]');

    // A manager holds reports.export by role, so it arrives inherited and on.
    await expect(exportPerm.getByText('არჩეული როლი ამ უფლებას ავტომატურად ანიჭებს')).toBeVisible();
    await expect(exportPerm.getByText('აქტიურია')).toBeVisible();

    // Changing the role re-derives the whole set, and an operator has no
    // defaults -- so the same permission must come back off. This is the
    // behaviour that makes the screen honest: what one role granted does not
    // silently survive into another.
    await modal.locator('select').first().selectOption('operator');
    await expect(exportPerm.getByText('არჩეული როლი ამ უფლებას ავტომატურად არ ანიჭებს')).toBeVisible();
    await expect(exportPerm.getByText('გამორთულია')).toBeVisible();

    await modal.locator('select').nth(1).selectOption('საინფორმაციო');
    await modal.locator('input[type="text"]').fill('E2E ახალი პოზიცია');

    // An explicit ALLOW on top of a role that does not grant it -- the case
    // the override table exists for.
    await exportPerm.locator('select').selectOption('ALLOW');
    await expect(exportPerm.getByText('აქტიურია')).toBeVisible();

    await modal.getByRole('button', { name: 'შენახვა' }).click();
    await expect(modal).toHaveCount(0);

    const list = await request.get('/api/users', { headers: auth });
    const saved = (await list.json()).find((u: { id: number }) => u.id === userId);
    expect(saved.role, 'the role select did not reach the payload').toBe('operator');
    expect(saved.department, 'the department select did not reach the payload').toBe('საინფორმაციო');
    expect(saved.position, 'the position field did not reach the payload').toBe('E2E ახალი პოზიცია');
    expect(saved.permissions, 'the explicit ALLOW did not reach the payload').toContain(
      'reports.export'
    );
  });
});
