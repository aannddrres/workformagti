import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Location } from '@angular/common';
import { TranslatePipe } from '@ngx-translate/core';
import { CategoriesService } from '../../core/services/categories.service';
import { ArticlesService } from '../../core/services/articles.service';
import { Category } from '../../core/models/category';
import { ArticleSummary } from '../../core/models/article';
import { ArticleList, ArticleListItem } from '../../shared/article-list/article-list';
import { CategoryStrip } from '../../shared/category-strip/category-strip';
import { categoryIconClass } from '../../shared/category-visuals';
import {
  buildRecursiveCategoryCounts,
  categoryPath,
  descendantCategoryIds,
} from '../../shared/category-tree';
import { isReaderVisibleArticle } from '../../shared/article-visibility';

@Component({
  selector: 'app-category-view-page',
  standalone: true,
  imports: [ArticleList, CategoryStrip, TranslatePipe, RouterLink],
  templateUrl: './category-view-page.html',
})
export class CategoryViewPage {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly categoriesService = inject(CategoriesService);
  private readonly articlesService = inject(ArticlesService);

  private readonly currentRouteKey = signal('');
  private readonly allArticles = signal<ArticleSummary[]>([]);
  protected readonly category = signal<Category | null>(null);
  protected readonly categories = signal<Category[]>([]);
  protected readonly rows = signal<ArticleListItem[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal(false);
  protected readonly pageSize = 40;
  protected readonly visibleLimit = signal(this.pageSize);
  protected readonly visibleRows = computed(() => this.rows().slice(0, this.visibleLimit()));
  protected readonly canLoadMore = computed(() => this.visibleRows().length < this.rows().length);

  protected readonly childCategories = computed(() => {
    const id = this.category()?.id;
    return id == null
      ? []
      : this.categories().filter((candidate) => candidate.parent_id === id && candidate.is_active);
  });
  protected readonly counts = computed(() =>
    buildRecursiveCategoryCounts(this.categories(), this.allArticles()),
  );
  protected readonly breadcrumbs = computed(() => {
    const id = this.category()?.id;
    const path = categoryPath(this.categories(), id ?? null);
    return path.slice(0, -1);
  });

  protected readonly icon = computed(() => {
    const cat = this.category();
    return categoryIconClass(cat);
  });

  constructor() {
    this.route.paramMap.subscribe((params) => {
      this.currentRouteKey.set(params.get('slug') ?? '');
      this.refreshCategory();
    });

    this.categoriesService.list().subscribe({
      next: (categories) => {
        this.categories.set(categories);
        this.articlesService.list({ limit: 1000 }).subscribe({
          next: (articles) => {
            this.allArticles.set(articles.filter(isReaderVisibleArticle));
            this.refreshCategory();
          },
          error: () => {
            this.loadError.set(true);
            this.loading.set(false);
          },
        });
      },
      error: () => {
        this.loadError.set(true);
        this.loading.set(false);
      },
    });
  }

  private refreshCategory(): void {
    if (this.categories().length === 0) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(false);
    const key = this.currentRouteKey();
    const found =
      this.categories().find(
        (candidate) =>
          candidate.is_active && (candidate.slug === key || String(candidate.id) === key),
      ) ?? null;
    this.category.set(found);
    this.visibleLimit.set(this.pageSize);
    if (!found) {
      this.rows.set([]);
      this.loading.set(false);
      return;
    }

    const ids = descendantCategoryIds(this.categories(), found.id);
    this.rows.set(
      this.allArticles()
        .filter((article) => article.category_id != null && ids.has(article.category_id))
        .map((article) => ({
          id: article.id,
          title: article.title,
          categoryName: article.category_name || found.name,
          categoryIcon: categoryIconClass(this.categories().find((c) => c.id === article.category_id) ?? found),
          createdAt: article.created_at,
          publishedAt: article.published_at,
        }))
        .sort((a, b) => a.title.localeCompare(b.title, 'ka')),
    );
    this.loading.set(false);
  }

  openArticle(id: number): void {
    this.articlesService.logView(id);
    this.router.navigate(['/article', id], { queryParams: { returnUrl: this.router.url } });
  }

  showMore(): void {
    this.visibleLimit.update((limit) => limit + this.pageSize);
  }

  goBack(): void {
    this.location.back();
  }
}
