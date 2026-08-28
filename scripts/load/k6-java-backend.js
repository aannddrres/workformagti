import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';

// Pool of pre-authenticated JWTs (fetch_tokens.sh). Deliberately NOT
// logging in per-VU here: AuthController's LoginRateLimiter is a real,
// intentional 10/minute-per-IP control (java-backend/.../security/
// LoginRateLimiter.java), and this whole test runs from one machine/IP --
// ramping concurrent fresh logins would just trip that limiter
// instantly and measure nothing about read-path capacity. Sharing a small
// token pool across all VUs isolates what this test is actually meant to
// observe: authenticated read-path capacity. The default 150 VUs represents
// the execution plan's normal-concurrency ceiling; TARGET_VUS=600 remains an
// explicit stress/capacity run, not the release-SLO default.
const tokens = new SharedArray('tokens', function () {
  return JSON.parse(open('./tokens.json'));
});

const BASE = __ENV.TARGET_BASE_URL || 'http://localhost:8090';
const TARGET_VUS = Number(__ENV.TARGET_VUS || 150);
const SEARCH_QUERY = encodeURIComponent(__ENV.SEARCH_QUERY || 'ინტერნეტი');

export const options = {
  scenarios: {
    representative_authenticated_reads: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: Math.max(1, Math.floor(TARGET_VUS / 3)) },
        { duration: '30s', target: TARGET_VUS },
        { duration: '60s', target: TARGET_VUS },
        { duration: '15s', target: 0 }
      ]
    }
  },
  thresholds: {
    // Release gates from the enterprise execution plan. k6 exits non-zero when
    // any threshold fails; these are not observational metrics.
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    'http_req_duration{name:global-search}': ['p(95)<2000']
  }
};

export default function () {
  const token = tokens[__VU % tokens.length];
  const headers = { Authorization: `Bearer ${token}` };

  const progressRes = http.get(`${BASE}/api/compliance/my-progress`, { headers, tags: { name: 'my-progress' } });
  check(progressRes, { 'my-progress 200': (r) => r.status === 200 });

  const readingsRes = http.get(`${BASE}/api/compliance/my-readings`, { headers, tags: { name: 'my-readings' } });
  check(readingsRes, { 'my-readings 200': (r) => r.status === 200 });

  const articlesRes = http.get(`${BASE}/api/articles`, { headers, tags: { name: 'articles-list' } });
  check(articlesRes, { 'articles 200': (r) => r.status === 200 });

  const newsRes = http.get(`${BASE}/api/news`, { headers, tags: { name: 'news-list' } });
  check(newsRes, { 'news 200': (r) => r.status === 200 });

  const searchRes = http.get(`${BASE}/api/search/global?q=${SEARCH_QUERY}`, {
    headers,
    tags: { name: 'global-search' }
  });
  check(searchRes, { 'global-search 200': (r) => r.status === 200 });

  sleep(0.5 + Math.random());
}
