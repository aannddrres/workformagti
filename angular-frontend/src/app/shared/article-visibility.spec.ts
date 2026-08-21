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
