#!/usr/bin/env node
/**
 * Fails when a translation key is referenced but not defined, or when the two
 * language files have drifted apart.
 *
 * ngx-translate renders a missing key as the key itself, so the failure looks
 * like a literal "common.retry" sitting where a button label should be. It
 * does not throw, the build passes and the unit tests pass -- which is how
 * three admin templates shipped referencing a `common` namespace that was
 * never created (audit FE-08). Nothing but opening that exact error state in
 * a browser would have shown it.
 *
 *   node scripts/check-i18n.mjs
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const langs = ['ka', 'en'];

const flatten = (obj, prefix = '') =>
  Object.entries(obj).flatMap(([k, v]) => {
    const key = prefix ? `${prefix}.${k}` : k;
    return v && typeof v === 'object' ? flatten(v, key) : [key];
  });

const keysOf = (lang) =>
  new Set(flatten(JSON.parse(readFileSync(join(root, `public/i18n/${lang}.json`), 'utf8'))));

const walk = (dir) =>
  readdirSync(dir).flatMap((entry) => {
    const p = join(dir, entry);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });

// Only the two static forms. A key built at runtime (`favorites.page.group_${t}`)
// cannot be checked from here and is deliberately out of scope -- reporting it
// as missing would train people to ignore this check.
const PIPE = /['"]([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)['"]\s*\|\s*translate/g;
const CALL = /(?:instant|stream|get)\(\s*['"]([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)['"]/g;

const used = new Set();
for (const file of walk(join(root, 'src'))) {
  if (!/\.(ts|html)$/.test(file) || file.endsWith('.spec.ts')) continue;
  const text = readFileSync(file, 'utf8');
  for (const re of [PIPE, CALL]) {
    for (const m of text.matchAll(re)) used.add(m[1]);
  }
}

const [ka, en] = langs.map(keysOf);
const problems = [];

for (const k of [...ka].filter((k) => !en.has(k)).sort()) problems.push(`in ka.json, missing from en.json: ${k}`);
for (const k of [...en].filter((k) => !ka.has(k)).sort()) problems.push(`in en.json, missing from ka.json: ${k}`);
for (const k of [...used].filter((k) => !ka.has(k)).sort()) problems.push(`used in code, defined nowhere: ${k}`);

if (problems.length) {
  console.error('i18n check failed:\n');
  for (const p of problems) console.error(`  ${p}`);
  console.error(`\n${problems.length} problem(s). A key with no translation renders as its own name.`);
  process.exit(1);
}
console.log(`i18n OK — ${ka.size} keys in both languages, ${used.size} referenced statically, none missing.`);
