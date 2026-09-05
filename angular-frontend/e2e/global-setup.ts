import { request as playwrightRequest } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { E2E_PASSWORD, SHARED_PERSONAS, TOKEN_CACHE } from './helpers';

/**
 * Logs the shared personas in ONCE per run and caches their tokens.
 *
 * LoginRateLimiter allows 10 attempts per account per minute
 * (LoginRateLimiter.java:70). The suite already spent four of admin's ten on
 * six specs; a spec file per feature area would blow through it and the
 * failures would look like broken tests rather than the rate limiter doing
 * its job. Per-run operator personas (test_operator_*) still log in for
 * themselves -- they are distinct accounts with their own allowance, and
 * their first login is what provisions them.
 *
 * This deliberately does not weaken the limiter for tests. The limiter is a
 * real defence and CI is the one place it gets exercised under load.
 */
export default async function globalSetup(): Promise<void> {
  const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:4201';
  const context = await playwrightRequest.newContext({ baseURL });
  const tokens: Record<string, string> = {};

  try {
    for (const email of SHARED_PERSONAS) {
      const res = await context.post('/api/auth/login', { data: { email, password: E2E_PASSWORD } });
      if (!res.ok()) {
        throw new Error(
          `global setup could not log in ${email}: ${res.status()} ${await res.text()}\n` +
            'Provide E2E_PASSWORD for pre-seeded accounts, or enable development JIT login for test accounts.'
        );
      }
      tokens[email] = (await res.json()).access_token as string;
    }
  } finally {
    await context.dispose();
  }

  mkdirSync(dirname(TOKEN_CACHE), { recursive: true });
  writeFileSync(TOKEN_CACHE, JSON.stringify(tokens), 'utf8');
}
