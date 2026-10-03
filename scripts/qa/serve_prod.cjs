// Serve the production Angular build and forward /api and /uploads to a QA
// backend -- what nginx does in the real image, minus TLS -- optionally over
// a slow line.
//
//   cd angular-frontend && npx ng build --configuration production
//   node scripts/qa/serve_prod.cjs [--port 4300] [--backend 8090] [--kbps 400] [--latency 300]
//
// Time anything network-related against THIS, not the dev server: dev
// bundles are unminified and several times larger. --kbps throttles every
// response body (both static files and API answers) to roughly that many
// kilobits per second; --latency adds that many milliseconds before each
// response starts. gzip is applied to static files only, as nginx does (PO-52).
// Node's standard library only; nothing to install.
'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

const arg = (name, dflt) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? Number(process.argv[i + 1]) : dflt;
};
const PORT = arg('port', 4300);
const BACKEND = arg('backend', 8090);
const KBPS = arg('kbps', 0);
const LATENCY = arg('latency', 0);
if ([8080, 8081, 8082].includes(BACKEND)) throw new Error('refusing another stack\'s backend port');

const ROOT = path.resolve(__dirname, '../../angular-frontend/dist/angular-frontend/browser');
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.txt': 'text/plain' };

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function sendThrottled(res, buf) {
  if (!KBPS) { res.end(buf); return; }
  const perTick = Math.max(1, Math.round((KBPS * 1000 / 8) / 10)); // bytes per 100 ms
  for (let i = 0; i < buf.length; i += perTick) {
    res.write(buf.subarray(i, i + perTick));
    await sleep(100);
  }
  res.end();
}

function collect(stream) {
  return new Promise((resolve, reject) => {
    const parts = [];
    stream.on('data', (c) => parts.push(c));
    stream.on('end', () => resolve(Buffer.concat(parts)));
    stream.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  if (LATENCY) await sleep(LATENCY);
  if (req.url.startsWith('/api/') || req.url.startsWith('/uploads/')) {
    const body = await collect(req);
    const up = http.request({ host: '127.0.0.1', port: BACKEND, path: req.url, method: req.method,
      headers: { ...req.headers, host: `127.0.0.1:${BACKEND}`, 'x-forwarded-for': '127.0.0.1' } },
    async (upRes) => {
      const data = await collect(upRes);
      const headers = { ...upRes.headers };
      delete headers['content-length'];
      delete headers['transfer-encoding'];
      res.writeHead(upRes.statusCode, headers);
      await sendThrottled(res, data);
    });
    up.on('error', () => { res.writeHead(502); res.end('backend unreachable'); });
    up.end(body);
    return;
  }
  let file = path.join(ROOT, decodeURIComponent(req.url.split('?')[0]));
  if (!file.startsWith(ROOT) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    file = path.join(ROOT, 'index.html'); // the SPA's own router takes it from here
  }
  let data = fs.readFileSync(file);
  const headers = { 'content-type': TYPES[path.extname(file)] || 'application/octet-stream' };
  if (/\bgzip\b/.test(req.headers['accept-encoding'] || '') && /\.(js|css|html|json|svg|txt)$/.test(file)) {
    data = zlib.gzipSync(data);
    headers['content-encoding'] = 'gzip';
  }
  res.writeHead(200, headers);
  await sendThrottled(res, data);
});
server.listen(PORT, '127.0.0.1', () =>
  console.log(`prod build on http://127.0.0.1:${PORT} -> backend :${BACKEND}` +
    (KBPS ? `, ${KBPS} kbit/s` : '') + (LATENCY ? `, +${LATENCY} ms` : '')));
