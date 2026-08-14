import { test, expect } from '@playwright/test';
import { apiLogin, createArticle, createTestOperator, firstCategoryId, loginAsUi, runId } from './helpers';

/**
 * Confirms department-scoped visibility actually holds in the UI, not just
 * at the API layer (already covered by Java integration tests): an
 * operator in one real department sees an article targeted at their
 * department and not one targeted at a different department, and vice
 * versa. Uses real Georgian department strings (matching DEPARTMENT_ORDER
 * in article-edit-drawer.ts) rather than the JIT test personas' English
 * placeholder departments, which don't match real dept-targeting values
 * (see the dept-group-hierarchy-in-free-text memory).
 */
test('operators in different departments see different mandatory-reading content', async ({ page, request }) => {
  const id = runId();
  const adminToken = await apiLogin(request, 'admin@magti.ge');
  const categoryId = await firstCategoryId(request, adminToken);

  const infoTitle = `E2E საინფორმაციო-სტატია ${id}`;
  const techTitle = `E2E ტექნიკური-სტატია ${id}`;
  await createArticle(request, adminToken, { title: infoTitle, categoryId, targetDepartments: ['საინფორმაციო'] });
  await createArticle(request, adminToken, { title: techTitle, categoryId, targetDepartments: ['ტექნიკური'] });

  const infoEmail = `test_operator_dept_info_${id}@magti.ge`;
  const techEmail = `test_operator_dept_tech_${id}@magti.ge`;
  await createTestOperator(request, adminToken, infoEmail, 'E2E Info Operator', 'საინფორმაციო');
  await createTestOperator(request, adminToken, techEmail, 'E2E Tech Operator', 'ტექნიკური');

  await loginAsUi(page, infoEmail);
  await page.goto('/info');
  await expect(page.getByText(infoTitle)).toBeVisible();
  await expect(page.getByText(techTitle)).not.toBeVisible();

  await loginAsUi(page, techEmail);
  await page.goto('/info');
  await expect(page.getByText(techTitle)).toBeVisible();
  await expect(page.getByText(infoTitle)).not.toBeVisible();
});
