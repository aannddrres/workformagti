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

  /** Eight named sizes plus twenty one-off bracket values is not a scale.
   *  tailwind.config.js now defines six steps with Georgian line heights and
   *  this rule keeps everything on them. `text-3xl` and up still resolve --
   *  they live in `extend`, so a stray one renders rather than vanishing --
   *  but it fails here. */
  it('uses only the six type steps', () => {
    const offenders = scan(/\b(?:[a-z-]+:)*text-(?:3xl|4xl|5xl|6xl|7xl|8xl|9xl)\b/g, () => true);
    const arbitrary = scan(/\b(?:[a-z-]+:)*text-\[[\d.]+(?:px|rem|em)\]/g, () => true);
    expect([...offenders, ...arbitrary]).toEqual([]);
  });

  /** Measured in the running app: Noto Sans Georgian renders 300, 400 and 500
   *  at an identical advance width, and 700, 800 and 900 likewise, because the
   *  family ships exactly three files. `font-medium` was used 98 times and
   *  changed nothing -- it read as a decision the typeface could not carry.
   *  Three weights exist, so three weights are allowed. */
  it('uses only the three weights the typeface actually has', () => {
    const offenders = scan(
      /\b(?:[a-z-]+:)*font-(?:thin|extralight|light|medium|extrabold|black)\b/g,
      () => true,
    );
    expect(offenders).toEqual([]);
  });

  /** Ten radii and five shadow tiers plus five bracket one-offs is not an
   *  elevation system: `shadow-xl` and `shadow-2xl` were used about equally,
   *  so no two dialogs sat at the same height. Three of each now, and
   *  `rounded-[10px]` -- a corner only the three core control classes could
   *  reach -- is gone. */
  it('uses only the three radii and the three elevations', () => {
    const radii = scan(/\b(?:[a-z-]+:)*rounded-(?:xl|2xl|3xl|\[[^\]]+\])/g, () => true);
    const shadows = scan(/\b(?:[a-z-]+:)*shadow-(?:sm|md|lg|xl|2xl|\[[^\]]+\])/g, () => true);
    expect([...radii, ...shadows]).toEqual([]);
  });

  /** `transition-all` animates layout properties as well as paint ones, which
   *  is what makes a hover lift stutter; 649 rendered elements carried it.
   *  Every use only needed colour, shadow and transform, which bare
   *  `transition` covers. Durations come from the three tokens rather than
   *  Tailwind's 150ms default, which was never chosen. */
  it('never animates every property, and takes durations from the scale', () => {
    const all = scan(/\b(?:[a-z-]+:)*transition-all\b/g, () => true);
    const raw = scan(/\b(?:[a-z-]+:)*duration-\d+\b/g, () => true);
    expect([...all, ...raw]).toEqual([]);
  });

  /** Page width was decided six different ways -- 1600, 1440, 1080, 920, 900,
   *  780 -- because every reader page hand-rolled its own container while the
   *  admin screens used `.portal-page`. Two widths now, both in styles.css.
   *  The reader column inside an article is exempt: it is a measure, not a
   *  page width. */
  it('takes page width from the shared classes', () => {
    const offenders: string[] = [];
    for (const file of sourceFiles()) {
      if (!file.endsWith('.html') || file.includes('article-detail')) continue;
      const text = shippedSource(readFileSync(file, 'utf8'));
      for (const match of text.matchAll(/class="[^"]*\bmx-auto w-full max-w-\[\d+px\][^"]*"/g)) {
        offenders.push(`${relative(APP_ROOT, file).split(sep).join('/')}  ${match[0].slice(7, 60)}`);
      }
    }
    expect([...new Set(offenders)]).toEqual([]);
  });

  /** Seventeen `window.confirm` calls and one `window.alert` put a grey
   *  operating-system dialog in front of deletes, bulk publishes, role moves
   *  and every unsaved-changes guard. It cannot be translated, themed, or told
   *  which record is about to go. ConfirmService and ToastService replace them.
   */
  it('never asks through a browser dialog', () => {
    const offenders = scan(/\bwindow\.(?:confirm|alert|prompt)\s*\(/g, () => true);
    expect(offenders).toEqual([]);
  });

  /** Three neutral ramps ran at once -- slate 977, zinc 809, gray 744 -- and 42
   *  files mixed two or three of them. slate is blue-tinted and zinc is not, so
   *  two admin screens one click apart read as two different products. slate
   *  wins because the shared classes in styles.css were already written in it.
   *
   *  Surfaces and borders do not become `slate-*`; they become the `--surface`
   *  / `--surface-muted` / `--border` tokens, which is also what fixes dark
   *  elevation: `zinc-900` sits just above the dark canvas, `slate-900` sits
   *  *below* it, so a blind family swap would have pushed every card behind
   *  the page it floats on. */
  it('uses one neutral ramp', () => {
    const offenders = scan(
      /\b(?:[a-z-]+:)*(?:bg|text|border|ring|divide|from|via|to|placeholder|decoration|outline|fill|stroke|accent|caret)-(?:gray|zinc|neutral|stone)-\d{2,3}\b/g,
      () => true,
    );
    expect(offenders).toEqual([]);
  });

  /** `.dialog-backdrop` is the dimmed layer behind a dialog, and it carries an
   *  entrance animation. The sweep that introduced it replaced every
   *  `bg-black/40`, which also caught two things that were never backdrops --
   *  the star over a video thumbnail and the video label chip -- so both
   *  faded in like an overlay on every render. A backdrop covers its parent,
   *  so the class only belongs beside `inset-0`. */
  it('keeps the backdrop class on backdrops', () => {
    const offenders = scan(/^.*\bdialog-backdrop\b.*$/gm, (m) => !/\binset-0\b/.test(m[0]));
    expect(offenders).toEqual([]);
  });

  /** `toLocaleDateString('ka-GE', ...)` renders the US month-first `08/19/2026`
   *  wherever Chromium ships no `ka` CLDR data, and Node -- where the tests run
   *  -- does ship it, so no unit test can catch the difference. Dates go
   *  through the deterministic helpers in shared/ka-date.ts instead. */
  it('never formats a date through the browser locale', () => {
    const offenders = scan(/toLocale(?:Date|Time)?String\s*\(/g, () => true);
    expect(offenders).toEqual([]);
  });

  /** DatePipe takes a free-form format string, and six templates each wrote
   *  their own, so one day read `29.09.2026` in a table and `29 სექ. 2026` in
   *  the row's history beside it. Templates use `kaDate`, whose shapes are the
   *  four in ka-date.ts (owner decision კ4). */
  it('shows dates in the four shapes ka-date.ts defines', () => {
    const offenders = scan(/\|\s*date\b|\bDatePipe\b/g, () => true);
    expect(offenders).toEqual([]);
  });

  /** Georgian has no case, but Chrome maps `text-transform: uppercase` onto
   *  Mtavruli, the capital-only script -- so 42 labels, table headers and
   *  section titles rendered in a letterform the rest of the page never used,
   *  and letter-spacing (up to 0.18em) pulled the joined Georgian shapes
   *  apart. Hierarchy comes from size, weight and colour instead
   *  (owner decision კ2, 2026-09-29). */
  it('never sets Georgian in capitals or spaces its letters', () => {
    const utilities = scan(/(?<![\w-])(?:[a-z-]+:)*(?:uppercase|tracking-[^\s"'`]+)(?![\w-])/g, () => true);
    const css = shippedSource(readFileSync(resolve(APP_ROOT, '../styles.css'), 'utf8'));
    const sheet = [...css.matchAll(/text-transform:\s*uppercase|@apply[^;]*\b(?:uppercase|tracking-\S+)/g)].map(
      (m) => `styles.css  ${m[0]}`,
    );
    expect([...utilities, ...sheet]).toEqual([]);
  });

  /** A page was named three times: the header bar repeated it, a red
   *  `.portal-eyebrow` above the heading gave it a second name, and the H1 a
   *  third -- "ჩემი სამუშაო რიგი" over "კონტენტის მართვა" on one screen. The
   *  H1 is the name now (owner decision კ8). */
  it('names a page once, in its heading', () => {
    const eyebrow = scan(/portal-eyebrow/g, () => true);
    const css = readFileSync(resolve(APP_ROOT, '../styles.css'), 'utf8');
    expect([...eyebrow, ...(css.includes('portal-eyebrow') ? ['styles.css  portal-eyebrow'] : [])]).toEqual([]);
  });

  /** Tabs came in three looks: a black pill that became a white blob in dark
   *  mode, a red pill and a red underline -- so the same control read as three
   *  different things one click apart. Every tab is `.portal-tab`, and every
   *  strip of them is `.portal-tabs` (owner decision კ5). */
  it('draws every tab the same way', () => {
    const tabs = scan(/<[a-z]+\b[^>]*\brole="tab"[^>]*>/g, (m) => !/class="[^"]*\bportal-tab\b/.test(m[0]));
    const strips = scan(/<[a-z]+\b[^>]*\brole="tablist"[^>]*>/g, (m) => !/class="[^"]*\bportal-tabs\b/.test(m[0]));
    expect([...tabs, ...strips]).toEqual([]);
  });

  /** Angular reads `[class.lg:w-[5.25rem]]` as the class `lg:w-[5` -- the name
   *  ends at the first dot -- so the collapsed menu hid its labels but kept
   *  its full width, and nothing failed. A class with a dot in it goes through
   *  `[ngClass]` or a plain `class` instead. */
  it('never binds a class whose name has a dot in it', () => {
    const offenders = scan(/\[class\.[^\]=\s]*\.[^=\s]*\]\s*=/g, () => true);
    expect(offenders).toEqual([]);
  });
});
