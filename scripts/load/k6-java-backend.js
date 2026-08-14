import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';

// Pool of pre-authenticated JWTs (fetch_tokens.sh). Deliberately NOT
// logging in per-VU here: AuthController's LoginRateLimiter is a real,
// intentional 10/minute-per-IP control (java-backend/.../security/
// LoginRateLimiter.java), and this whole test runs from one machine/IP --
// ramping to 600 concurrent fresh logins would just trip that limiter
// instantly and measure nothing about read-path capacity. Sharing a small
// token pool across all VUs isolates what this test is actually meant to
// observe: whether HikariCP's default connection pool (10) and Tomcat's
// default thread pool (200) -- application.yml configures neither -- hold
// up under ~600 concurrent authenticated read requests.
const tokens = new SharedArray('tokens', function () {
  return JSON.parse(open('./tokens.json'));
});

const BASE = __ENV.TARGET_BASE_URL || 'http://localhost:8090';

export const options = {
  scenarios: {
    ramp_to_600: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: 200 },
        { duration: '30s', target: 600 },
        { duration: '30s', target: 600 },
        { duration: '15s', target: 0 }
      ]
    }
  },
  thresholds: {
    http_req_failed: ['rate<1.0'] // observational -- report the real number either way
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

  sleep(0.5 + Math.random());
}
