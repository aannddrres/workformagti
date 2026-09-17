import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Subject, catchError, debounceTime, map, of, switchMap } from 'rxjs';
import { CategoriesService } from '../../core/services/categories.service';
import { ArticlesService } from '../../core/services/articles.service';
import { Category } from '../../core/models/category';
import { Article, ArticleSummary } from '../../core/models/article';
import { ArticleCard, ArticleCardViewModel } from '../../shared/article-card/article-card';
import { CategoryTile } from '../../shared/category-tile/category-tile';
import { isRecentlyPublished } from '../../shared/category-visuals';
import {
  buildRecursiveCategoryCounts,
  categoryPath,
  descendantCategoryIds,
} from '../../shared/category-tree';
import { isReaderVisibleArticle } from '../../shared/article-visibility';

@Component({
  selector: 'app-knowledge-base-page',
  standalone: true,
  imports: [ArticleCard, CategoryTile, TranslatePipe],
  templateUrl: './knowledge-base-page.html',
})
export class KnowledgeBasePage {
  private readonly categoriesService = inject(CategoriesService);
  private readonly articlesService = inject(ArticlesService);
  private readonly translate = inject(TranslateService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly categories = signal<Category[]>([]);
  protected readonly topLevelCategories = computed(() =>
    this.categories().filter(
      (category) =>
        category.parent_id == null &&
        category.is_active &&
        (this.categoryCounts().get(category.id) ?? 0) > 0,
    ),
  );
  protected readonly activeCategories = computed(() =>
    this.categories().filter((category) => category.is_active),
  );

  private readonly resultCards = signal<ArticleCardViewModel[]>([]);
  protected readonly pageSize = 40;
  protected readonly visibleLimit = signal(this.pageSize);
  protected readonly cards = computed(() => this.resultCards().slice(0, this.visibleLimit()));
  protected readonly canLoadMore = computed(() => this.cards().length < this.resultCards().length);
  protected readonly loading = signal(true);
  protected readonly errorMessage = signal<string | null>(null);

  /** Broader (but still bounded, unlike the original's 1000-article client
   *  cache) dataset used only to compute the bento grid's per-category
   *  counts and "recent" indicators -- decoupled from `cards`, which
   *  reflects the current search/filter. */
  protected readonly countingSet = signal<ArticleSummary[]>([]);
  protected readonly categoryCounts = computed(() =>
    buildRecursiveCategoryCounts(this.categories(), this.countingSet()),
  );

  protected readonly searchQuery = signal('');
  protected readonly selectedCategoryId = signal<number | null>(null);

  private readonly search$ = new Subject<{ q: string; categoryId: number | null }>();

  constructor() {
    this.search$
      .pipe(
        debounceTime(300),
        switchMap(({ q, categoryId }) => this.fetch(q, categoryId)),
      )
      .subscribe(({ cards, error }) => {
        this.resultCards.set(cards);
        this.visibleLimit.set(this.pageSize);
        this.errorMessage.set(error);
        this.loading.set(false);
      });

    const initialQuery = this.route.snapshot.queryParamMap.get('q')?.trim() ?? '';
    const initialCategory = Number(this.route.snapshot.queryParamMap.get('category')) || null;
    this.searchQuery.set(initialQuery);
    this.selectedCategoryId.set(initialCategory);

    this.categoriesService.list().subscribe({
      next: (categories) => {
        this.categories.set(categories);
        this.articlesService.list({ limit: 1000 }).subscribe({
          next: (articles) => {
            this.countingSet.set(articles.filter(isReaderVisibleArticle));
            // The current filters, not the ones the page opened with: anything
            // typed while this list was loading is already in them, and
            // re-running the opening query here silently replaced it.
            this.search$.next({ q: this.searchQuery(), categoryId: this.selectedCategoryId() });
          },
          error: () => {
            this.loading.set(false);
            this.errorMessage.set(this.translate.instant('articles.kb_page.search_error'));
          },
        });
      },
      error: () => {
        this.loading.set(false);
        this.errorMessage.set(this.translate.instant('articles.kb_page.search_error'));
      },
    });
  }

  private uncategorizedLabel(): string {
    return this.translate.instant('articles.card.uncategorized');
  }

  private fromSummary(a: ArticleSummary): ArticleCardViewModel {
    return {
      id: a.id,
      title: a.title,
      categoryName: a.category_name || this.uncategorizedLabel(),
      createdAt: a.created_at,
      publishedAt: a.published_at,
      readTime: a.read_time,
    };
  }

  private fromFullArticle(a: Article, query: string): ArticleCardViewModel {
    const categoryName =
      this.categories().find((c) => c.id === a.category_id)?.name || this.uncategorizedLabel();
    const normalizedQuery = query.trim().toLocaleLowerCase('ka');
    return {
      id: a.id,
      title: a.title,
      categoryName,
      createdAt: a.created_at,
      publishedAt: a.published_at,
      readTime: a.read_time,
      categoryContext:
        categoryPath(this.categories(), a.category_id)
          .map((category) => category.name)
          .join(' › ') || categoryName,
      excerpt: this.buildExcerpt(a.content, query),
      targetDepartments: a.target_departments,
      matchKind:
        normalizedQuery && a.title.toLocaleLowerCase('ka').includes(normalizedQuery)
          ? 'title'
          : 'other',
    };
  }

  private buildExcerpt(content: string, query: string): string {
    const document = new DOMParser().parseFromString(content || '', 'text/html');
    const text = (document.body.innerText || document.body.textContent || '')
      .replace(/\s+/g, ' ')
      .trim();
    if (!text) {
      return '';
    }

    const normalized = text.toLocaleLowerCase('ka');
    const needle = query.trim().toLocaleLowerCase('ka');
    const match = needle ? normalized.indexOf(needle) : -1;
    const start = match > 70 ? match - 70 : 0;
    const end = Math.min(text.length, match >= 0 ? match + needle.length + 150 : 220);
    return `${start > 0 ? '…' : ''}${text.slice(start, end).trim()}${end < text.length ? '…' : ''}`;
  }

  private fetch(q: string, categoryId: number | null) {
    this.loading.set(true);
    const trimmed = q.trim();
    const categoryIds =
      categoryId == null ? null : descendantCategoryIds(this.categories(), categoryId);

    const request$ = trimmed
      ? this.articlesService.search(trimmed).pipe(
          map((articles) =>
            articles.filter(
              (article) =>
                isReaderVisibleArticle(article) &&
                (categoryIds == null ||
                  (article.category_id != null && categoryIds.has(article.category_id))),
            ),
          ),
          map((articles) => articles.map((article) => this.fromFullArticle(article, trimmed))),
        )
      : of(
          this.countingSet()
            .filter(
              (article) =>
                categoryIds == null ||
                (article.category_id != null && categoryIds.has(article.category_id)),
            )
            .map((article) => this.fromSummary(article))
            .sort((a, b) => a.title.localeCompare(b.title, 'ka')),
        );

    return request$.pipe(
      map((cards) => ({ cards, error: null as string | null })),
      catchError(() =>
        of({
          cards: [] as ArticleCardViewModel[],
          error: this.translate.instant('articles.kb_page.search_error') as string,
        }),
      ),
    );
  }

  onSearchInput(value: string): void {
    this.searchQuery.set(value);
    this.persistFilters();
    this.search$.next({ q: value, categoryId: this.selectedCategoryId() });
  }

  onCategoryFilterChange(value: string): void {
    const categoryId = value ? Number(value) : null;
    this.selectedCategoryId.set(categoryId);
    this.persistFilters();
    this.search$.next({ q: this.searchQuery(), categoryId });
  }

  private persistFilters(): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {
        q: this.searchQuery().trim() || null,
        category: this.selectedCategoryId(),
      },
      replaceUrl: true,
    });
  }

  showMore(): void {
    this.visibleLimit.update((limit) => limit + this.pageSize);
  }

  openArticle(id: number): void {
    this.articlesService.logView(id);
    this.router.navigate(['/article', id], { queryParams: { returnUrl: this.router.url } });
  }

  hasRecentInCategory(category: Category): boolean {
    const ids = descendantCategoryIds(this.categories(), category.id);
    return this.countingSet().some(
      (article) =>
        article.category_id != null &&
        ids.has(article.category_id) &&
        isRecentlyPublished({ publishedAt: article.published_at, createdAt: article.created_at }),
    );
  }
}
