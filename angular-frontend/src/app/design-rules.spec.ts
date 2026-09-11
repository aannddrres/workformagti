import { readFileSync, readdirSync } from 'node:fs';
import { extname, join, relative, resolve, sep } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Rules the visual layer has to keep, expressed as tests because a style
 * convention nobody can fail is a style convention that drifts. The repository
 * already does this on the backend (`DocumentedFactsTest`, the access-contract
 * coverage tests); this is the frontend equivalent.
 *
 * Each rule here records a defect that actually shipped -- see the comment on
 * the rule -- so deleting one should mean the class of defect is impossible,
 * not that it became inconvenient.
 */

const APP_ROOT = resolve(process.cwd(), 'src/app');

function sourceFiles(): string[] {
  const found: string[] = [];
  const walk = (dir: string): void => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full);
        continue;
      }
      const ext = extname(entry.name);
      if ((ext !== '.ts' && ext !== '.html') || entry.name.endsWith('.spec.ts')) continue;
      found.push(full);
    }
  };
  walk(APP_ROOT);
  return found;
}

/** Comments are documentation, not shipped markup. A rule that read them would
 *  forbid explaining why the rule exists. */
function shippedSource(text: string): string {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1');
}

function scan(pattern: RegExp, keep: (match: RegExpMatchArray) => boolean): string[] {
  const offenders: string[] = [];
  for (const file of sourceFiles()) {
    const text = shippedSource(readFileSync(file, 'utf8'));
    for (const match of text.matchAll(pattern)) {
      if (keep(match)) offenders.push(`${relative(APP_ROOT, file).split(sep).join('/')}  ${match[0]}`);
    }
  }
  return [...new Set(offenders)].sort();
}

describe('design rules', () => {
  /** 12px is the legibility floor. Georgian needs more x-height than Latin, and
   *  an audit of one knowledge-base screen found 91 elements below WCAG AA,
   *  most of them 10px secondary text. */
  it('never sets type below the 12px floor', () => {
    const offenders = scan(/text-\[(\d+(?:\.\d+)?)(px|rem)\]/g, (m) => {
      const px = m[2] === 'rem' ? Number(m[1]) * 16 : Number(m[1]);
      return px < 12;
    });
    expect(offenders).toEqual([]);
  });

  /** Tailwind drops an unknown shade silently, so the element simply loses its
   *  border or background and nobody sees a failure. `border-gray-150` did this
   *  in 10 places and `bg-brand-50` left selected admin rows unmarked. */
  it('only uses colour shades that exist', () => {
    const TAILWIND_FAMILIES =
      'slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose';
    const REAL_SHADES = new Set(['50', '100', '200', '300', '400', '500', '600', '700', '800', '900', '950']);
    const stock = scan(
      new RegExp(String.raw`\b(?:${TAILWIND_FAMILIES})-(\d{2,3})\b`, 'g'),
      (m) => !REAL_SHADES.has(m[1]),
    );

    // tailwind.config.js declares these shades and no others, each backed by a
    // token in styles.css. `danger` has no 50 on purpose -- destructive actions
    // do not get a wash.
    const CONFIGURED: Record<string, Set<string>> = {
      brand: new Set(['50', '600', '700']),
      danger: new Set(['600', '700']),
    };
    const project = scan(/\b(brand|danger)-(\d{2,3})\b/g, (m) => !CONFIGURED[m[1]].has(m[2]));

    expect([...stock, ...project]).toEqual([]);
  });

  /** `gray-400` on white is 2.54:1 -- it was the most common secondary text
   *  colour in the app and it failed WCAG AA by a wide margin.
   *
   *  600 is the floor, not 500. A 500 passes on pure white (slate-500 is
   *  4.76:1) but most of this app is not white: `--canvas` is `#F6F8FA` and
   *  the category tiles sit on a tint above it, where the same colour measures
   *  4.42:1. Measured in the running app, that off-by-a-hair was the only
   *  contrast failure left after the 400s were fixed.
   *
   *  Two variants are exempt. `dark:` paints light text on a dark surface,
   *  where pale is correct. `placeholder:` sits inside a `bg-white` control,
   *  which is the one light surface where 500 does pass. */
  it('never puts pale text on a light surface', () => {
    const offenders = scan(
      /((?:[a-z-]+:)*)text-(?:gray|slate|zinc)-(?:300|400|500)\b/g,
      (m) => !m[1].includes('dark:') && !m[1].includes('placeholder:'),
    );
    expect(offenders).toEqual([]);
  });

  /** angular-frontend/AGENTS.md: "A new surface needs its `dark:` variants from
   *  the start -- a missing one shows as unreadable text, not as an error."
   *  116 class attributes had a light text colour and no dark counterpart, so
   *  they painted `slate-700` on the dark canvas at 2.7:1. Only templates are
   *  covered; class strings built in TypeScript are checked by eye. */
  it('always pairs a light text colour with a dark one', () => {
    const lightText = /(?<!dark:)\btext-(?:gray|slate|zinc)-(?:600|700|800|900|950)\b/;
    const offenders: string[] = [];
    for (const file of sourceFiles()) {
      if (!file.endsWith('.html')) continue;
      const text = shippedSource(readFileSync(file, 'utf8'));
      for (const attribute of text.matchAll(/class="([^"]*)"/g)) {
        const classes = attribute[1];
        if (lightText.test(classes) && !/\bdark:text-/.test(classes)) {
          offenders.push(`${relative(APP_ROOT, file).split(sep).join('/')}  ${classes.slice(0, 70)}`);
        }
      }
    }
    expect([...new Set(offenders)]).toEqual([]);
  });

  /** `toLocaleDateString('ka-GE', ...)` renders the US month-first `08/19/2026`
   *  wherever Chromium ships no `ka` CLDR data, and Node -- where the tests run
   *  -- does ship it, so no unit test can catch the difference. Dates go
   *  through the deterministic helpers in shared/ka-date.ts instead. */
  it('never formats a date through the browser locale', () => {
    const offenders = scan(/toLocale(?:Date|Time)?String\s*\(/g, () => true);
    expect(offenders).toEqual([]);
  });
});
