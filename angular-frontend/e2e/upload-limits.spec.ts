import { test, expect } from '@playwright/test';
import { apiLogin } from './helpers';

/** A PDF of exactly `bytes`, real enough for the upload's magic-byte check. */
function pdfOf(bytes: number): Buffer {
  const head = Buffer.from('%PDF-1.4\n');
  const tail = Buffer.from('\n%%EOF\n');
  return Buffer.concat([head, Buffer.alloc(bytes - head.length - tail.length, 0x30), tail]);
}

// In production every request passes through the frontend's nginx after the
// ingress (k8s/50-ingress.yaml routes / to portal-frontend). That nginx had
// no body limit of its own, so its 1MB default refused any larger upload with
// its own HTML page before Spring saw it -- and nothing noticed, because the
// suite ran against ng serve. CI now runs it against the production image, so
// this spec is the one that would have caught it.
test('an upload under the application limit reaches Spring; one over it gets the application\'s answer', async ({ request }) => {
  const token = await apiLogin(request, 'content@magti.ge');
  const upload = (bytes: number) => request.post('/api/upload', {
    headers: { Authorization: `Bearer ${token}` },
    multipart: { file: { name: 'limit.pdf', mimeType: 'application/pdf', buffer: pdfOf(bytes) } }
  });

  const fiveMegabytes = await upload(5 * 1024 * 1024);
  expect(fiveMegabytes.status(), 'a 5MB PDF must pass every proxy on the way').toBe(200);
  expect((await fiveMegabytes.json()).url).toMatch(/^\/uploads\/.+\.pdf$/);

  // Between the application's 10MB and the transport's 11MB: the refusal has
  // to be Spring's, with its reason, not a proxy's error page.
  const overTheLimit = await upload(Math.round(10.5 * 1024 * 1024));
  expect(overTheLimit.status()).toBe(413);
  expect((await overTheLimit.json()).detail).toContain('10 MiB');
});
