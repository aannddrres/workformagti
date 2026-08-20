/**
 * Fails when a template asks for a translation key that neither locale file
 * defines, or when the two locales disagree about which keys exist.
 *
 * The reason this exists: `common.retry` was used in three places on the
 * content-admin screen and defined in neither ka.json nor en.json. ngx-translate
 * renders a missing key as the key itself, so the retry button under the
 * category filter, the video drawer and the article drawer all read literally
 * "common.retry" on screen -- in both languages, silently, with nothing failing.
 * A missing key is invisible to the compiler and to every unit test that stubs
 * the translate pipe, so it needs its own check.
 *
 * Only string literals are matched. Keys assembled at runtime
 * (`'x.' + kind | translate`) are out of reach here by design; catching those
 * would need the running app, which is what the E2E specs are for.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const LOCALES = ['ka', 'en'];

function walk(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) walk(path, out);
    else if (/\.(html|ts)$/.test(path) && !/\.spec\.ts$/.test(path)) out.push(path);
  }
  return out;
}

function flatten(node, trail = '', into = new Set()) {
  for (const [key, value] of Object.entries(node)) {
    const path = trail ? `${trail}.${key}` : key;
    if (value && typeof value === 'object' && !Array.isArray(value)) flatten(value, path, into);
    else into.add(path);
  }
  return into;
}

const defined = Object.fromEntries(
  LOCALES.map((locale) => [
    locale,
    flatten(JSON.parse(readFileSync(join(root, 'public/i18n', `${locale}.json`), 'utf8')))
  ])
);

// `'a.b.c' | translate`, and the programmatic forms.
const USED = [
  /'([a-z][a-zA-Z0-9_.]*)'\s*\|\s*translate/g,
  /translate\.(?:instant|get|stream)\(\s*'([a-z][a-zA-Z0-9_.]*)'/g
];

const missing = [];
for (const file of walk(join(root, 'src'))) {
  const source = readFileSync(file, 'utf8');
  for (const pattern of USED) {
    for (const [, key] of source.matchAll(pattern)) {
      if (!key.includes('.')) continue; // not a translation path
      const absent = LOCALES.filter((locale) => !defined[locale].has(key));
      if (absent.length) {
        missing.push(`${file.replace(root + '/', '')}: ${key} (missing in ${absent.join(', ')})`);
      }
    }
  }
}

// The locales are also checked against each other: a key present in one and
// not the other renders as a raw key for exactly the users of one language,
// which is the harder version of the same bug to notice.
const [ka, en] = LOCALES.map((locale) => defined[locale]);
const onlyKa = [...ka].filter((key) => !en.has(key));
const onlyEn = [...en].filter((key) => !ka.has(key));

let failed = false;
if (missing.length) {
  failed = true;
  console.error('Translation keys used but not defined:');
  for (const line of [...new Set(missing)].sort()) console.error(`  ${line}`);
}
for (const [label, keys] of [
  ['defined in ka.json but not en.json', onlyKa],
  ['defined in en.json but not ka.json', onlyEn]
]) {
  if (keys.length) {
    failed = true;
    console.error(`Keys ${label}:`);
    for (const key of keys.sort()) console.error(`  ${key}`);
  }
}

if (failed) process.exit(1);
console.log(`i18n: every literal key resolves, and ${ka.size} keys match across ${LOCALES.length} locales`);
