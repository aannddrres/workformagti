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

export async function apiLogin(request: APIRequestContext, email: string, password = 'x'): Promise<string> {
  const hit = cachedToken(email);
  if (hit) {
    return hit;
  }
  const res = await request.post('/api/auth/login', { data: { email, password } });
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
      password: 'NotUsedJitBypass1!',
      team_id: null
    }
  });
  expect(res.ok(), `create test operator failed: ${res.status()} ${await res.text()}`).toBeTruthy();
  const user = await res.json();
  return user.id as number;
}

/** Logs the given persona into the Angular app by obtaining a real JWT via
 *  the API (faster + less flaky than typing through the login form every
 *  time) and seeding it into localStorage before the app bootstraps. The
 *  dedicated login-flow scenarios in auth.spec.ts still drive the real
 *  form -- this helper is for scenarios where login itself isn't what's
 *  under test. */
export async function loginAsUi(page: Page, email: string, password = 'x'): Promise<void> {
  const token = await apiLogin(page.request, email, password);
  await seedTokenIntoPage(page, token);
}

/** Same as {@link loginAsUi} but for a token already obtained elsewhere in
 *  the test -- avoids a second, redundant /api/auth/login call against the
 *  10/minute-per-IP LoginRateLimiter when the same persona (typically
 *  admin@magti.ge) both drives API fixture setup and the UI in one test. */
export async function seedTokenIntoPage(page: Page, token: string): Promise<void> {
  await page.goto('/login');
  await page.evaluate((t) => localStorage.setItem('magti_token', t), token);
  await page.goto('/');
  await expect(page).not.toHaveURL(/\/login/);
}
