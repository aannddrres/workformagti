import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import { Subject, of, throwError } from 'rxjs';
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

  /** UI audit bug 14: the category row only shows categories that have
   *  articles, so when the article list failed the categories went too. */
  it('keeps the categories when the articles fail, and loads them again on retry', () => {
    const roaming = { id: 5, name: 'როუმინგი', parent_id: null, is_active: true, icon: 'fa-plane', sort_order: 0 };
    let fail = true;
    const list = vi.fn(() => (fail ? throwError(() => new Error('down')) : of([{ ...wanted, category_id: 5 }])));
    TestBed.configureTestingModule({
      providers: [
        { provide: CategoriesService, useValue: { list: () => of([roaming]) } },
        { provide: ArticlesService, useValue: { list, search: vi.fn(() => of([])) } },
        { provide: TranslateService, useValue: { instant: (key: string) => key } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
        { provide: Router, useValue: { navigate: vi.fn() } }
      ]
    });
    const page = TestBed.runInInjectionContext(() => new KnowledgeBasePage()) as unknown as {
      topLevelCategories: () => Array<{ id: number }>;
      errorMessage: () => string | null;
      cards: () => Array<{ title: string }>;
      retry: () => void;
    };

    expect(page.errorMessage()).toBe('articles.kb_page.search_error');
    expect(page.topLevelCategories().map((category) => category.id)).toEqual([5]);

    fail = false;
    page.retry();
    vi.advanceTimersByTime(300);

    expect(list).toHaveBeenCalledTimes(2);
    expect(page.errorMessage()).toBeNull();
    expect(page.cards().map((card) => card.title)).toEqual(['E2E რჩეული']);
  });
});
