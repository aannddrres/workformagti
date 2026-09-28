import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';

// One staging-only synthetic identity per VU. This file contains short-lived
// JWTs and is gitignored. IT provisions these accounts through the real
// InfoPortal test client; this script never bypasses its login policy.
const users = new SharedArray('staging synthetic users', () => JSON.parse(open('./staging-users.json')));
const base = __ENV.TARGET_BASE_URL;
const vus = 600;
const identityChecked = new Counter('identity_checked');
const identityMismatch = new Counter('identity_mismatch');
const writeAttempted = new Counter('write_attempted');
const writeFailure = new Counter('write_failure');

if (__ENV.LOAD_CONFIRM !== 'isolated-synthetic-staging' || !base || !/^https:\/\//.test(base)) {
  fail('Set LOAD_CONFIRM=isolated-synthetic-staging and an HTTPS TARGET_BASE_URL for isolated staging');
}
if (users.length !== vus) fail(`Expected ${vus} distinct synthetic users, got ${users.length}`);
const emails = new Set();
for (const user of users) {
  if (!user.email || !user.email.startsWith('loadtest.') || !user.token ||
      !Number.isSafeInteger(user.articleId) || user.articleId <= 0 || emails.has(user.email)) {
    fail('staging-users.json needs 600 distinct loadtest.* identities with tokens and visible article IDs');
  }
  emails.add(user.email);
}

export const options = {
  scenarios: {
    six_hundred_distinct_users: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '2m', target: vus },
        { duration: '5m', target: vus },
        { duration: '1m', target: 0 }
      ],
      gracefulRampDown: '30s'
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    'http_req_duration{name:global-search}': ['p(95)<2000'],
    identity_checked: ['count==600'],
    identity_mismatch: ['count==0'],
    write_attempted: ['count==600'],
    write_failure: ['count==0']
  }
};

export default function () {
  const user = users[__VU - 1];
  const headers = { Authorization: `Bearer ${user.token}` };
  if (__ITER === 0) {
    const me = http.get(`${base}/api/users/me`, { headers, tags: { name: 'identity' } });
    let actual = '';
    try { actual = me.json('email'); } catch (_) { /* Count malformed identity as mismatch. */ }
    const identityOk = me.status === 200 && actual === user.email;
    identityChecked.add(1);
    identityMismatch.add(identityOk ? 0 : 1);
    if (!identityOk) {
      fail(`Identity mismatch for VU ${__VU}`);
    }
    // A passive article view writes a separate audit/evidence row each time.
    // One per VU makes the expected distinct-writer count exactly 600.
    const view = http.post(`${base}/api/articles/${user.articleId}/view`, null,
      { headers, tags: { name: 'article-view-write' } });
    writeAttempted.add(1);
    writeFailure.add(check(view, { 'article-view write 200': (r) => r.status === 200 }) ? 0 : 1);
  }

  const progress = http.get(`${base}/api/compliance/my-progress`, { headers, tags: { name: 'my-progress' } });
  check(progress, { 'my-progress 200': (r) => r.status === 200 });
  const readings = http.get(`${base}/api/compliance/my-readings`, { headers, tags: { name: 'my-readings' } });
  check(readings, { 'my-readings 200': (r) => r.status === 200 });
  const articles = http.get(`${base}/api/articles`, { headers, tags: { name: 'articles-list' } });
  check(articles, { 'articles 200': (r) => r.status === 200 });
  const search = http.get(`${base}/api/search/global?q=${encodeURIComponent('ინტერნეტი')}`,
    { headers, tags: { name: 'global-search' } });
  check(search, { 'global-search 200': (r) => r.status === 200 });
  sleep(0.5 + Math.random());
}
