import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import { Subject, of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { KnowledgeBasePage } from './knowledge-base-page';
import { ArticlesService } from '../../core/services/articles.service';
import { CategoriesService } from '../../core/services/categories.service';

const published = { status: 'published', is_draft: false, published_at: '2026-09-01T00:00:00Z', created_at: '2026-09-01T00:00:00Z', read_time: 1, category_id: null, category_name: null };
const wanted = { ...published, id: 2, title: 'E2E რჩეული', content: '' };
const other = { ...published, id: 1, title: 'AAAALPHA', content: '' };

describe('KnowledgeBasePage search', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  /** The page reloads its results once the article list arrives. It used to
   *  reload them for the query the page was OPENED with, so anything typed
   *  while the list was still on its way was dropped: the box kept the words,
   *  the grid showed every article. That made the E2E favourite-star spec fail
   *  intermittently on 2026-09-17 -- its search box read the title while the
   *  grid started at "AAAALPHA". */
  it('keeps what was typed while the article list was still loading', () => {
    const list$ = new Subject<unknown[]>();
    const search = vi.fn(() => of([wanted]));
    TestBed.configureTestingModule({
      providers: [
        { provide: CategoriesService, useValue: { list: () => of([]) } },
        { provide: ArticlesService, useValue: { list: () => list$, search } },
        { provide: TranslateService, useValue: { instant: (key: string) => key } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
        { provide: Router, useValue: { navigate: vi.fn() } }
      ]
    });
    const page = TestBed.runInInjectionContext(() => new KnowledgeBasePage());

    page.onSearchInput('E2E რჩეული');
    list$.next([other, wanted]);
    list$.complete();
    vi.advanceTimersByTime(300);

    expect(search).toHaveBeenCalledWith('E2E რჩეული');
    const cards = (page as unknown as { cards: () => Array<{ title: string }> }).cards();
    expect(cards.map((card) => card.title)).toEqual(['E2E რჩეული']);
  });
});
