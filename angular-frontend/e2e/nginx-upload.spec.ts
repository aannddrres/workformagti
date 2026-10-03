import { expect, test } from '@playwright/test';

const mib = 1024 * 1024;
const png = (size: number) => {
  const bytes = Buffer.alloc(size, 42);
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(bytes);
  return bytes;
};

test('shipping nginx accepts permitted uploads and preserves the 10 MiB business cap', async ({ request }) => {
  const index = await request.get('/');
  expect(index.headers()['server']).toMatch(/^nginx/);
  expect(await index.text()).not.toContain('/@vite/client');
  console.log(`nginx image=${process.env.NGINX_SMOKE_IMAGE_ID}`);
  const login = await request.post('/api/auth/login', { data: { email: 'admin@magti.ge', password: 'fixture' } });
  expect(login.status()).toBe(200);
  const headers = { Authorization: `Bearer ${(await login.json()).access_token}` };

  for (const size of [2 * mib, 10 * mib]) {
    const bytes = png(size);
    const upload = await request.post('/api/upload', {
      headers, multipart: { file: { name: `boundary-${size}.png`, mimeType: 'image/png', buffer: bytes } },
    });
    expect(upload.status(), `upload ${size} bytes through nginx`).toBe(200);
    const stored = await upload.json();
    const download = await request.get(stored.url, { headers });
    expect(download.status()).toBe(200);
    expect((await download.body()).equals(bytes), `download matches ${size} bytes`).toBe(true);
    // Kept but revalidated on every view since 2026-10-02 (UploadedFileController).
    expect(download.headers()['cache-control']).toBe('no-cache, private');
    console.log(`upload bytes=${size} status=200 downloaded-bytes-match=true`);
  }

  for (const size of [10 * mib + 1, 11 * mib + 1]) {
    const rejected = await request.post('/api/upload', {
      headers, multipart: { file: { name: 'too-large.png', mimeType: 'image/png', buffer: png(size) } },
    });
    expect(rejected.status(), `reject ${size} bytes`).toBe(413);
    console.log(`upload bytes=${size} status=413`);
  }
  const wrongType = await request.post('/api/upload', {
    headers, multipart: { file: { name: 'fake.png', mimeType: 'image/png', buffer: Buffer.from('<html>fake</html>') } },
  });
  expect(wrongType.status()).toBe(415);
});

test('shipping nginx waits beyond the former 60 second upstream default', async ({ request }) => {
  const slowURL = process.env.NGINX_SMOKE_SLOW_URL;
  expect(slowURL, 'runner must provide a separate nginx with a synthetic slow upstream').toBeTruthy();
  const response = await request.get(`${slowURL}/api/slow`, { timeout: 100_000 });
  expect(response.status()).toBe(200);
  expect(await response.text()).toBe('synthetic delayed response');
});
