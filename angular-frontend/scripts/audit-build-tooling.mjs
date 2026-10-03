#!/usr/bin/env node
/**
 * `npm audit --audit-level=moderate` over the whole tree, build tooling
 * included, with a short dated list of advisories the owner accepted.
 *
 * npm audit itself has no way to accept one advisory, so a single unfixable
 * finding would turn the gate red for every other one as well. Each entry
 * below names the advisory, why it does not reach anyone, and a date after
 * which the gate fails again, so an exception is looked at rather than kept
 * by habit. An advisory missing from the list fails exactly as before.
 *
 *   node scripts/audit-build-tooling.mjs
 */
import { execFileSync } from 'node:child_process';

const ACCEPTED = [
  {
    // Owner decision, 2026-10-03 (PR #38). braces <= 3.0.3 has no patched
    // version; only tailwindcss 3 pulls it in (via chokidar, micromatch and
    // fast-glob), and only at build time, on glob patterns this repository
    // writes. Nothing a user sends reaches it. npm's offered fix is the
    // tailwindcss 4 major upgrade, which restyles every screen.
    id: 'GHSA-vfj7-8cjw-p6xm',
    until: '2026-11-03',
  },
];

const RANK = { info: 0, low: 1, moderate: 2, high: 3, critical: 4 };
const today = new Date().toISOString().slice(0, 10);

let report;
try {
  report = execFileSync('npm', ['audit', '--json'], { encoding: 'utf8', shell: process.platform === 'win32' });
} catch (e) {
  // npm audit exits non-zero whenever it finds anything; the JSON is still on stdout.
  report = e.stdout;
}
const { vulnerabilities = {} } = JSON.parse(report);

// Only advisory objects count; a string in `via` is a package vulnerable
// through another one, which is judged by that package's own advisories.
const advisories = new Map();
for (const pkg of Object.values(vulnerabilities)) {
  for (const via of pkg.via) {
    if (typeof via === 'object' && RANK[via.severity] >= RANK.moderate) {
      advisories.set(via.url, via);
    }
  }
}

const failures = [];
for (const [url, adv] of advisories) {
  const accepted = ACCEPTED.find((a) => url.endsWith(a.id));
  if (!accepted) {
    failures.push(`${adv.severity} ${adv.name}: ${adv.title} (${url})`);
  } else if (today > accepted.until) {
    failures.push(`${accepted.id} was accepted until ${accepted.until}; look at it again (${url})`);
  } else {
    console.log(`accepted until ${accepted.until}: ${adv.name} ${accepted.id}`);
  }
}
for (const a of ACCEPTED) {
  if (![...advisories.keys()].some((url) => url.endsWith(a.id))) {
    console.log(`${a.id} no longer reported; remove it from ACCEPTED.`);
  }
}

if (failures.length) {
  console.error(`${failures.length} advisory(ies) at moderate or above:\n  ${failures.join('\n  ')}`);
  process.exit(1);
}
console.log('No unaccepted advisories at moderate or above.');
