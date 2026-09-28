import { expect, test } from '@playwright/test';
import { apiLogin, seedTokenIntoPage } from './helpers';

test('1920×1080 Georgian workspace stays usable in both themes by keyboard', async ({ page, request }, testInfo) => {
  const token = await apiLogin(request, 'admin@magti.ge');
  await page.setViewportSize({ width: 1920, height: 1080 });
  await seedTokenIntoPage(page, token);

  const theme = page.getByRole('button', { name: 'თემის გადართვა' });
  await expect(theme).toBeVisible();
  await expect(page.getByRole('link', { name: 'სიახლეები' })).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);

  const shellColor = () => page.locator('app-shell > div').first().evaluate((el) => getComputedStyle(el).backgroundColor);
  const lightColor = await shellColor();
  await testInfo.attach('workspace-light-1920x1080', {
    body: await page.screenshot(),
    contentType: 'image/png'
  });

  await theme.focus();
  await page.keyboard.press('Enter');
  await expect.poll(() => page.evaluate(() => document.documentElement.classList.contains('dark'))).toBe(true);
  await expect.poll(shellColor).not.toBe(lightColor);
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await testInfo.attach('workspace-dark-1920x1080', {
    body: await page.screenshot(),
    contentType: 'image/png'
  });

  await page.getByRole('link', { name: 'სიახლეები' }).click();
  await expect(page).toHaveURL(/\/news$/);
  await expect.poll(() => page.evaluate(() => document.documentElement.classList.contains('dark'))).toBe(true);
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
