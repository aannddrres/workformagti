import { APIRequestContext, Page, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/** Unique per-run suffix so repeated E2E runs never collide on unique
 *  constraints (category name, tag_mapping, user email) left over from a
 *  previous run against the same isolated Oracle instance. */
export function runId(): string {
  return Date.now().toString(36);
}

/** The fixed personas every spec shares. global-setup logs these in once for
 *  the whole run; see its docstring for why (LoginRateLimiter: 10 attempts
 *  per account per minute). */
export const SHARED_PERSONAS = ['admin@magti.ge', 'content@magti.ge', 'info@magti.ge'];
export const TOKEN_CACHE = join(__dirname, '.auth', 'tokens.json');
export const E2E_PASSWORD = process.env.E2E_PASSWORD ?? 'x';
const AUTO_PROVISION_TEST_USERS = process.env.E2E_AUTO_PROVISION === 'true';

let cache: Record<string, string> | null = null;

function cachedToken(email: string): string | undefined {
  if (cache === null) {
    try {
      cache = JSON.parse(readFileSync(TOKEN_CACHE, 'utf8'));
    } catch {
      // No cache file (a spec run outside the configured project, say).
      // Falling back to a real login is correct; it is only the shared
      // personas across many specs that need the allowance conserved.
      cache = {};
    }
  }
  return cache![email];
}

export async function apiLogin(request: APIRequestContext, email: string, password = E2E_PASSWORD): Promise<string> {
  const hit = cachedToken(email);
  if (hit) {
    return hit;
  }
  let res = await request.post('/api/auth/login', { data: { email, password } });
  if (!res.ok() && AUTO_PROVISION_TEST_USERS && email.startsWith('test_operator_')) {
    const adminToken = cachedToken('admin@magti.ge');
    expect(adminToken, 'admin token is required to provision a local QA operator').toBeTruthy();
    const created = await request.post('/api/users', {
      headers: { Authorization: `Bearer ${adminToken}` },
      data: {
        email,
        name: `QA Operator ${runId()}`,
        department: 'Support',
        position: null,
        phone: null,
        role: 'operator',
        password: E2E_PASSWORD,
        team_id: null
      }
    });
    expect(
      created.ok(),
      `auto-provision failed for ${email}: ${created.status()} ${await created.text()}`
    ).toBeTruthy();
    res = await request.post('/api/auth/login', { data: { email, password: E2E_PASSWORD } });
  }
  expect(res.ok(), `login failed for ${email}: ${res.status()} ${await res.text()}`).toBeTruthy();
  const body = await res.json();
  return body.access_token as string;
}

function authHeaders(token: string) {
  return { Authorization: `Bearer ${token}` };
}

/**
 * A category to hang article fixtures off, creating one if the schema is
 * empty.
 *
 * This used to assert that the database already had one, which was true of
 * the hand-maintained dev instance these specs were written against and is
 * false of a container that Flyway just migrated: no migration seeds a
 * category. Requiring pre-existing data is what kept these specs from ever
 * running in CI.
 */
export async function firstCategoryId(request: APIRequestContext, token: string): Promise<number> {
  const res = await request.get('/api/categories', { headers: authHeaders(token) });
  expect(res.ok(), `GET /api/categories failed: ${res.status()}`).toBeTruthy();
  const categories = await res.json();
  if (categories.length > 0) {
    return categories[0].id;
  }

  return (await createCategory(request, token, `E2E ბაზისური კატეგორია ${runId()}`)).id;
}

/** Only the fields CategoryRequest actually declares (name, parent_id, slug,
 *  icon, pastel_color_class); name is the sole @NotBlank. */
export async function createCategory(
  request: APIRequestContext,
  token: string,
  name: string
): Promise<{ id: number; name: string }> {
  const res = await request.post('/api/categories', {
    headers: authHeaders(token),
    data: { name, parent_id: null }
  });
  expect(res.ok(), `create category failed: ${res.status()} ${await res.text()}`).toBeTruthy();
  const category = await res.json();
  return { id: category.id as number, name: category.name as string };
}

export interface CreateArticleOptions {
  title: string;
  categoryId: number;
  targetDepartments: string[];
  quizEnabled?: boolean;
  status?: string;
  content?: string;
}

function articleBody(opts: CreateArticleOptions) {
  return {
    title: opts.title,
    content: opts.content ?? '<p>E2E fixture content.</p>',
    category_id: opts.categoryId,
    tags: null,
    target_departments: opts.targetDepartments,
    status: opts.status ?? 'published',
    published_at: null,
    attachment_url: null,
    audience_profile: 'all',
    visible_to_tech_info: true,
    visible_to_service_center: false,
    is_draft: false,
    quiz_enabled: opts.quizEnabled ?? false,
    notify_operators: false
  };
}

export async function createArticle(
  request: APIRequestContext,
  token: string,
  opts: CreateArticleOptions
): Promise<number> {
  const res = await request.post('/api/articles', {
    headers: authHeaders(token),
    data: articleBody(opts)
  });
  expect(res.ok(), `create article failed: ${res.status()} ${await res.text()}`).toBeTruthy();
  const article = await res.json();
  return article.id as number;
}

/** A full-body PUT, which is what produces an article_history row -- the
 *  fixture the history modal needs and cannot be given any other way. */
export async function updateArticle(
  request: APIRequestContext,
  token: string,
  articleId: number,
  opts: CreateArticleOptions
): Promise<void> {
  const res = await request.put(`/api/articles/${articleId}`, {
    headers: authHeaders(token),
    data: articleBody(opts)
  });
  expect(res.ok(), `update article failed: ${res.status()} ${await res.text()}`).toBeTruthy();
}

export async function deleteArticleApi(request: APIRequestContext, token: string, articleId: number): Promise<void> {
  await request.delete(`/api/articles/${articleId}`, { headers: authHeaders(token) });
}

/** One question, two answers, `correctIndex` marks which answer is correct. */
export async function setQuiz(
  request: APIRequestContext,
  token: string,
  articleId: number,
  questionText: string,
  answers: string[],
  correctIndex: number
): Promise<void> {
  const res = await request.put(`/api/articles/${articleId}/quiz/admin`, {
    headers: authHeaders(token),
    data: {
      questions: [
        {
          id: null,
          question_text: questionText,
          position: 0,
          answers: answers.map((text, i) => ({
            id: null,
            answer_text: text,
            is_correct: i === correctIndex,
            position: i
          }))
        }
      ]
    }
  });
  expect(res.ok(), `set quiz failed: ${res.status()} ${await res.text()}`).toBeTruthy();
}

export async function syncRequiredReading(
  request: APIRequestContext,
  token: string,
  itemId: number,
  targetDepartment: string,
  dueDateIso: string
): Promise<number> {
  const res = await request.post('/api/compliance/required-readings', {
    headers: authHeaders(token),
    data: {
      item_type: 'article',
      item_id: itemId,
      target_department: targetDepartment,
      due_date: dueDateIso,
      priority: 'high'
    }
  });
  expect(res.ok(), `sync required reading failed: ${res.status()} ${await res.text()}`).toBeTruthy();
  const reading = await res.json();
  return reading.id as number;
}

export async function markRead(request: APIRequestContext, token: string, readingId: number): Promise<void> {
  const res = await request.post(`/api/compliance/mark-read/${readingId}`, { headers: authHeaders(token) });
  expect(res.ok(), `mark-read failed: ${res.status()} ${await res.text()}`).toBeTruthy();
}

export async function createTestOperator(
  request: APIRequestContext,
  adminToken: string,
  email: string,
  name: string,
  department: string
): Promise<number> {
  const res = await request.post('/api/users', {
    headers: authHeaders(adminToken),
    data: {
      email,
      name,
      department,
      position: null,
      phone: null,
      role: 'operator',
      password: E2E_PASSWORD,
      team_id: null
    }
  });
  expect(res.ok(), `create test operator failed: ${res.status()} ${await res.text()}`).toBeTruthy();
  const user = await res.json();
  return user.id as number;
}

/** Logs the given persona into the Angular app by obtaining a real JWT via
 *  the API (faster + less flaky than typing through the login form every
 *  time) and placing it in the same httpOnly cookie used by production. The
 *  dedicated login-flow scenarios in auth.spec.ts still drive the real
 *  form -- this helper is for scenarios where login itself isn't what's
 *  under test. */
export async function loginAsUi(page: Page, email: string, password = E2E_PASSWORD): Promise<void> {
  const token = await apiLogin(page.request, email, password);
  await seedTokenIntoPage(page, token);
}

/** Same as {@link loginAsUi} but for a token already obtained elsewhere in
 *  the test -- avoids a second, redundant /api/auth/login call against the
 *  10/minute-per-IP LoginRateLimiter when the same persona (typically
 *  admin@magti.ge) both drives API fixture setup and the UI in one test. */
export async function seedTokenIntoPage(page: Page, token: string): Promise<void> {
  await page.goto('/login');
  const origin = new URL(page.url()).origin;
  const secure = origin.startsWith('https:');
  await page.context().addCookies([{
    // PortalProperties.Cookie#sessionCookieName: prefixed wherever cookies are Secure.
    name: secure ? '__Host-access_token' : 'access_token',
    value: token,
    url: origin,
    httpOnly: true,
    secure,
    sameSite: 'Lax'
  }]);
  await page.goto('/');
  await expect(page).not.toHaveURL(/\/login/);
}

/**
 * The account each picker path resolves to -- the inverse of
 * {@code Login.resolvedEmail} (login.ts:62). If that method changes, this is
 * the one place a spec should have to follow it to.
 */
const PICKER_PATHS: Record<string, { role: string; dept?: string; group?: number; person?: number }> = {
  'admin@magti.ge': { role: 'სისტემური ადმინი' },
  'content@magti.ge': { role: 'კონტენტ-ადმინი', person: 1 },
  'manager@magti.ge': { role: 'ჯგუფის უფროსი', dept: 'ტექნიკური', group: 1 },
  'tech@magti.ge': { role: 'ოპერატორი', dept: 'ტექნიკური', group: 1, person: 1 },
  'info@magti.ge': { role: 'ოპერატორი', dept: 'საინფორმაციო', group: 1, person: 1 }
};

/**
 * Signs in through the login screen the way a person does, and resolves with
 * the /api/auth/login response so a caller can assert on it.
 *
 * The screen stopped being four fixed persona buttons in ac5cc7e: it is a
 * cascading picker now -- role, then department and group, then which of the
 * ten operators -- because the demo org has ~600 accounts and four buttons
 * could reach none of the seeded leaders or operators. Six specs drove the
 * old screen and were not updated with it; that alone is why the E2E job
 * failed, three months after it was made to pass.
 *
 * So specs name the ACCOUNT and this resolves the clicks. The next change to
 * the picker is one edit here rather than six, and the failure it produces
 * names {@link PICKER_PATHS} rather than a button caption in six files.
 *
 * An address the picker cannot reach -- nino@magti.ge is one; the picker's
 * first operator slot is tech@ or info@ -- falls through to the screen's own
 * "სხვა ანგარიშით შესვლა" field. That is the same dev-login path behind the
 * same loopback gate, not a test-only back door.
 */
export async function signInAsPersona(page: Page, email: string, options: { onCurrentPage?: boolean } = {}) {
  // onCurrentPage: sign in on the login screen already open -- the one a
  // sign-out leaves behind -- instead of loading it afresh, which would wipe
  // whatever the previous person left in memory before anyone could see it.
  if (!options.onCurrentPage) {
    await page.goto('/login');
  }
  const path = PICKER_PATHS[email];
  const loginResponse = page.waitForResponse((r) => r.url().includes('/api/auth/login'));

  if (path) {
    // Scoped through the <label> rather than getByLabel: the <select> sits
    // INSIDE its label here, so the label's text content carries every option
    // caption with it and an exact-name match would never hit.
    const selectFor = (labelText: string) =>
      page.locator('label', { hasText: labelText }).locator('select');

    await page.getByRole('button', { name: path.role, exact: true }).click();
    if (path.dept) {
      await selectFor('დეპარტამენტი').selectOption({ label: path.dept });
    }
    if (path.group) {
      await selectFor('ჯგუფი').selectOption(String(path.group));
    }
    if (path.person) {
      // The person select is captioned with the role itself: "ოპერატორი" for
      // an operator, "კონტენტ-ადმინი" for a content admin.
      await selectFor(path.role).selectOption(String(path.person));
    }
    // Two buttons on this screen read "შესვლა"; the picker's is the primary
    // one, the escape hatch's is secondary.
    await page.locator('button.primary-button', { hasText: 'შესვლა' }).click();
  } else {
    await page.locator('#uat-email').fill(email);
    await page.locator('button.secondary-button', { hasText: 'შესვლა' }).click();
  }

  return loginResponse;
}

/**
 * Answers the portal's own confirmation dialog.
 *
 * Deletes, archives, restores and bulk role moves used to ask through
 * window.confirm, and the specs answered with
 * `page.once('dialog', (dialog) => dialog.accept())` registered before the
 * click. 143fb6c replaced every one of those with ConfirmService, which draws
 * an ordinary in-page dialog instead. A native-dialog listener is then never
 * called, nothing presses the in-page button, and the action never happens --
 * the spec times out on whatever it expected next, naming that expectation
 * rather than the question nobody answered.
 *
 * An in-page dialog does not block the page the way a native one does, so it
 * is answered AFTER the click that raised it. Scoped to app-confirm-host
 * because the question can open on top of another dialog -- restoring a
 * version from the history modal does -- where a bare getByRole('dialog')
 * would match two.
 *
 * The action button is taken by position: the host renders Cancel, then the
 * action. Its caption is chosen per call site ("დიახ", "წაშლა", ...), and
 * naming captions here would bring back the many-file edit this helper exists
 * to prevent.
 */
export async function acceptConfirmation(page: Page): Promise<void> {
  const dialog = page.locator('app-confirm-host').getByRole('dialog');
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button').last().click();
  await expect(dialog).toHaveCount(0);
}
