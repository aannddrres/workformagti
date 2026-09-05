import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { isReaderVisibleArticle } from './article-visibility';

describe('isReaderVisibleArticle', () => {
  it('hides archived and draft material from reader surfaces', () => {
    expect(
      isReaderVisibleArticle({ status: 'archived', published_at: null, is_draft: false }),
    ).toBe(false);
    expect(
      isReaderVisibleArticle({ status: 'published', published_at: null, is_draft: true }),
    ).toBe(false);
  });

  it('shows published and already-started scheduled material', () => {
    expect(
      isReaderVisibleArticle({ status: 'published', published_at: null, is_draft: false }),
    ).toBe(true);
    expect(
      isReaderVisibleArticle({
        status: 'scheduled',
        published_at: '2020-01-01T00:00:00Z',
        is_draft: false,
      }),
    ).toBe(true);
    expect(
      isReaderVisibleArticle({
        status: 'scheduled',
        published_at: '2999-01-01T00:00:00Z',
        is_draft: false,
      }),
    ).toBe(false);
  });
});

/**
 * The other half of this rule lives in the backend, in
 * ge.magti.portal.article.ArticleVisibility. The list screens filter
 * client-side before the server is asked, so the lifecycle clause is written
 * twice -- and until 2026-09-05 nothing connected the two copies, so an edit
 * to one would have left the other quietly disagreeing.
 *
 * docs/api-contract/article-visibility-cases.json is now the single statement
 * of that clause. ArticleVisibilityParityTest runs it against the Java rule;
 * this runs the same file against the TypeScript one. A case added there
 * obliges both sides.
 */
describe('article-visibility parity with the backend rule', () => {
  interface VisibilityCase {
    name: string;
    status: string;
    publishedAt: 'past' | 'future' | null;
    visible: boolean;
  }

  // Far enough either side of now that no clock difference matters. The
  // backend compares against a fixed +04:00 offset and the browser against
  // the viewer's local clock; see the fixture's own note.
  const DAY = 24 * 60 * 60 * 1000;
  const dateFor = (when: VisibilityCase['publishedAt']): string | null =>
    when === null ? null : new Date(Date.now() + (when === 'past' ? -DAY : DAY)).toISOString();

  const fixturePath = resolve(process.cwd(), '../docs/api-contract/article-visibility-cases.json');
  const cases: VisibilityCase[] = JSON.parse(readFileSync(fixturePath, 'utf8')).cases;

  it('reads the shared fixture', () => {
    // Guards the guard: every assertion below loops over this file, so an
    // empty or unreadable one would pass while proving nothing.
    expect(cases.length).toBeGreaterThanOrEqual(8);
    expect(cases.some((c) => c.visible)).toBe(true);
    expect(cases.some((c) => !c.visible)).toBe(true);
  });

  for (const testCase of cases) {
    it(testCase.name, () => {
      expect(
        isReaderVisibleArticle({
          status: testCase.status,
          published_at: dateFor(testCase.publishedAt),
          is_draft: false,
        }),
      ).toBe(testCase.visible);
    });
  }
});
