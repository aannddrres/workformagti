import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { Subject, catchError, debounceTime, map, of, switchMap } from 'rxjs';
import { CategoriesService } from '../../core/services/categories.service';
import { ArticlesService } from '../../core/services/articles.service';
import { Category } from '../../core/models/category';
import { Article, ArticleSummary } from '../../core/models/article';
import { ArticleCard, ArticleCardViewModel } from '../../shared/article-card/article-card';
import { CategoryTile } from '../../shared/category-tile/category-tile';
import { isRecentlyPublished } from '../../shared/category-visuals';

@Component({
  selector: 'app-knowledge-base-page',
  standalone: true,
  imports: [ArticleCard, CategoryTile, TranslatePipe],
  templateUrl: './knowledge-base-page.html'
})
export class KnowledgeBasePage {
  private readonly categoriesService = inject(CategoriesService);
  private readonly articlesService = inject(ArticlesService);
  private readonly translate = inject(TranslateService);
  private readonly router = inject(Router);

  protected readonly categories = signal<Category[]>([]);
  protected readonly topLevelCategories = computed(() => this.categories().filter((c) => !c.parent_id));

  protected readonly cards = signal<ArticleCardViewModel[]>([]);
  protected readonly loading = signal(true);
  protected readonly errorMessage = signal<string | null>(null);

  /** Broader (but still bounded, unlike the original's 1000-article client
   *  cache) dataset used only to compute the bento grid's per-category
   *  counts and "recent" indicators -- decoupled from `cards`, which
   *  reflects the current search/filter. */
  protected readonly countingSet = signal<ArticleCardViewModel[]>([]);
  protected readonly categoryCounts = computed(() => {
    const counts = new Map<string, number>();
    for (const card of this.countingSet()) {
      counts.set(card.categoryName, (counts.get(card.categoryName) ?? 0) + 1);
    }
    return counts;
  });

  protected readonly searchQuery = signal('');
  protected readonly selectedCategoryId = signal<number | null>(null);

  private readonly search$ = new Subject<{ q: string; categoryId: number | null }>();

  constructor() {
    this.categoriesService.list().subscribe((categories) => this.categories.set(categories));
    this.articlesService
      .list({ limit: 200 })
      .subscribe((articles) => this.countingSet.set(articles.map((a) => this.fromSummary(a))));

    this.search$
      .pipe(
        debounceTime(300),
        switchMap(({ q, categoryId }) => this.fetch(q, categoryId))
      )
      .subscribe(({ cards, error }) => {
        this.cards.set(cards);
        this.errorMessage.set(error);
        this.loading.set(false);
      });

    this.search$.next({ q: '', categoryId: null });
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
      readTime: a.read_time
    };
  }

  private fromFullArticle(a: Article): ArticleCardViewModel {
    const categoryName = this.categories().find((c) => c.id === a.category_id)?.name || this.uncategorizedLabel();
    return {
      id: a.id,
      title: a.title,
      categoryName,
      createdAt: a.created_at,
      publishedAt: a.published_at,
      readTime: a.read_time
    };
  }

  private fetch(q: string, categoryId: number | null) {
    this.loading.set(true);
    const trimmed = q.trim();

    const request$ =
      !trimmed && categoryId == null
        ? this.articlesService.list({ limit: 40 }).pipe(map((articles) => articles.map((a) => this.fromSummary(a))))
        : this.articlesService.search(trimmed, categoryId ?? undefined).pipe(map((articles) => articles.map((a) => this.fromFullArticle(a))));

    return request$.pipe(
      map((cards) => ({ cards, error: null as string | null })),
      catchError(() => of({ cards: [] as ArticleCardViewModel[], error: this.translate.instant('articles.kb_page.search_error') as string }))
    );
  }

  onSearchInput(value: string): void {
    this.searchQuery.set(value);
    this.search$.next({ q: value, categoryId: this.selectedCategoryId() });
  }

  onCategoryFilterChange(value: string): void {
    const categoryId = value ? Number(value) : null;
    this.selectedCategoryId.set(categoryId);
    this.search$.next({ q: this.searchQuery(), categoryId });
  }

  openArticle(id: number): void {
    this.articlesService.logView(id);
    this.router.navigate(['/article', id]);
  }

  hasRecentInCategory(category: Category): boolean {
    return this.countingSet().some((card) => card.categoryName === category.name && isRecentlyPublished(card));
  }
}
