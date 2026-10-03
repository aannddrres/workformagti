import { expect, test } from '@playwright/test';
import { connect } from 'node:net';

/**
 * The shipping nginx's own answers (scripts/verify-nginx-smoke.sh runs this
 * against the freshly built image; the main suite skips it because locally it
 * may face ng serve, which sets none of this). ASVS V3.4.3-V3.4.6, V4.1.1,
 * V4.2.1, V13.4.3 and V13.4.4.
 */

/** One raw HTTP/1.1 exchange, for requests no HTTP client will send as written. */
function raw(baseURL: string, request: string): Promise<string> {
  const url = new URL(baseURL);
  return new Promise((resolve, reject) => {
    const socket = connect(Number(url.port), url.hostname, () => socket.write(request));
    let answer = '';
    socket.setTimeout(10_000, () => socket.destroy(new Error('no answer within 10 s')));
    socket.on('data', (chunk) => {
      answer += chunk.toString('latin1');
      if (answer.includes('\r\n\r\n')) {
        socket.end();
      }
    });
    socket.on('end', () => resolve(answer));
    socket.on('close', () => resolve(answer));
    socket.on('error', reject);
  });
}

test('the page and its assets carry the security headers', async ({ request }) => {
  const page = await request.get('/');
  const headers = page.headers();
  expect(headers['content-type']).toBe('text/html; charset=utf-8');
  expect(headers['x-content-type-options']).toBe('nosniff');
  expect(headers['x-frame-options']).toBe('DENY');
  expect(headers['referrer-policy']).toBe('no-referrer');
  const csp = headers['content-security-policy'];
  for (const directive of ["object-src 'none'", "script-src 'self'", "frame-ancestors 'none'", "base-uri 'self'", "connect-src 'self'"]) {
    expect(csp, directive).toContain(directive);
  }
  expect(csp).not.toContain('unsafe-eval');

  const bundle = (await page.text()).match(/src="(main-[A-Za-z0-9_-]+\.js)"/)?.[1];
  expect(bundle, 'index.html names its main bundle').toBeTruthy();
  const script = await request.get(`/${bundle}`);
  expect(script.headers()['content-type']).toBe('application/javascript; charset=utf-8');
  expect(script.headers()['x-content-type-options']).toBe('nosniff');

  const translations = await request.get('/i18n/ka.json');
  expect(translations.headers()['content-type']).toBe('application/json; charset=utf-8');

  // The backend's answers pass through untouched and carry Spring's own.
  const api = await request.get('/api/health');
  expect(api.headers()['x-content-type-options']).toBe('nosniff');
  expect(api.headers()['content-security-policy']).toContain("frame-ancestors 'none'");
});

/** QA round 4, 2026-10-02: the portal's own files compressed, the backend's answers left alone (BREACH). */
test('bundles are compressed and API answers are not', async ({ request }) => {
  const page = await request.get('/', { headers: { 'Accept-Encoding': 'gzip' } });
  const bundle = (await page.text()).match(/src="(main-[A-Za-z0-9_-]+\.js)"/)?.[1];
  const script = await request.get(`/${bundle}`, { headers: { 'Accept-Encoding': 'gzip' } });
  expect(script.headers()['content-encoding']).toBe('gzip');
  const translations = await request.get('/i18n/ka.json', { headers: { 'Accept-Encoding': 'gzip' } });
  expect(translations.headers()['content-encoding']).toBe('gzip');
  const api = await request.get('/api/health', { headers: { 'Accept-Encoding': 'gzip' } });
  expect(api.headers()['content-encoding']).toBeUndefined();
});

/** Request smuggling: a body whose length is declared two ways is refused, not guessed. */
test('a request that declares two body lengths is refused', async ({ baseURL }) => {
  const answer = await raw(baseURL!, [
    'POST /api/auth/login HTTP/1.1',
    `Host: ${new URL(baseURL!).host}`,
    'Content-Type: application/json',
    'Content-Length: 4',
    'Transfer-Encoding: chunked',
    '',
    '0',
    '',
    ''
  ].join('\r\n'));
  expect(answer.split('\r\n')[0]).toMatch(/^HTTP\/1\.1 400 /);
});

test('no directory listing and no TRACE', async ({ request, baseURL }) => {
  for (const directory of ['/i18n/', '/fonts/', '/media/']) {
    const answer = await request.get(directory);
    expect(await answer.text(), directory).not.toMatch(/Index of|<pre><a href/);
  }
  const trace = await raw(baseURL!, `TRACE / HTTP/1.1\r\nHost: ${new URL(baseURL!).host}\r\nX-Probe: reflected\r\n\r\n`);
  expect(trace.split('\r\n')[0]).toMatch(/^HTTP\/1\.1 405 /);
  expect(trace).not.toContain('X-Probe: reflected');
});
