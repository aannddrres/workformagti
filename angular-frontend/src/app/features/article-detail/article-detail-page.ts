import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Location } from '@angular/common';
import { map, switchMap } from 'rxjs';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ArticlesService } from '../../core/services/articles.service';
import { CategoriesService } from '../../core/services/categories.service';
import { Article, ArticleSummary, RelatedArticle } from '../../core/models/article';
import { Category } from '../../core/models/category';
import { formatArticleContent } from '../../shared/format-article-content';
import { ArticleVersionHistoryOverlay } from './article-version-history-overlay/article-version-history-overlay';
import { ReadingConfirm } from '../reading/reading-confirm/reading-confirm';
import { FavoriteStar } from '../../shared/favorite-star/favorite-star';
import { categoryPath } from '../../shared/category-tree';
import { isReaderVisibleArticle } from '../../shared/article-visibility';

@Component({
  selector: 'app-article-detail-page',
  standalone: true,
  imports: [ReadingConfirm, TranslatePipe, RouterLink, ArticleVersionHistoryOverlay, FavoriteStar],
  templateUrl: './article-detail-page.html',
})
export class ArticleDetailPage {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly articlesService = inject(ArticlesService);
  private readonly categoriesService = inject(CategoriesService);
  private readonly translate = inject(TranslateService);

  protected readonly loading = signal(true);
  protected readonly notFound = signal(false);
  protected readonly loadError = signal(false);
  protected readonly article = signal<Article | null>(null);
  protected readonly categories = signal<Category[]>([]);
  protected readonly linkableArticles = signal<ArticleSummary[]>([]);
  protected readonly relatedArticles = signal<RelatedArticle[]>([]);
  protected readonly showHistory = signal(false);

  protected readonly formattedContent = computed(() => {
    const targets = this.linkableArticles().map(({ id, title }) => ({ id, title }));
    return formatArticleContent(this.article()?.content, targets, this.article()?.title);
  });

  protected readonly categoryPath = computed(() => {
    const categoryId = this.article()?.category_id;
    if (categoryId == null) {
      return [];
    }

    return categoryPath(this.categories(), categoryId);
  });

  protected readonly siblingNavigation = computed(() => {
    const current = this.article();
    if (!current || current.category_id == null) {
      return { previous: null, next: null };
    }
    const siblings = this.linkableArticles()
      .filter((candidate) => candidate.category_id === current.category_id)
      .sort((a, b) => a.title.localeCompare(b.title, 'ka'));
    const index = siblings.findIndex((candidate) => candidate.id === current.id);
    return {
      previous: index > 0 ? siblings[index - 1] : null,
      next: index >= 0 && index < siblings.length - 1 ? siblings[index + 1] : null,
    };
  });

  protected readonly returnUrl = computed(() => {
    const value = this.route.snapshot.queryParamMap.get('returnUrl');
    return value?.startsWith('/') && !value.startsWith('//') ? value : null;
  });

  protected readonly navigationQueryParams = computed(() => {
    const returnUrl = this.returnUrl();
    return returnUrl ? { returnUrl } : {};
  });

  protected readonly metaLine = computed(() => {
    const a = this.article();
    if (!a) {
      return '';
    }
    const dept = a.target_departments.join(', ');
    const version = this.translate.instant('articles.detail_page.version_label', {
      version: a.version || 1,
    });
    const readTime = this.translate.instant('articles.card.read_time', { minutes: a.read_time });
    const parts = [dept, readTime, version].filter((p) => p);
    return parts.join(' · ');
  });

  /**
   * `tags` is stored as one comma-joined string and used to be glued onto the
   * end of the meta line, so an article opened with
   * `... · Support,esim,sim,ბილინგი` -- raw data with no spaces after the
   * commas, reading as a database field rather than as part of the page.
   */
  protected readonly tags = computed(() => {
    const raw = this.article()?.tags || '';
    const seen = new Set<string>();
    return raw
      .split(',')
      .map((tag) => tag.trim())
      .filter((tag) => tag.length > 0 && !seen.has(tag.toLocaleLowerCase('ka-GE')) && seen.add(tag.toLocaleLowerCase('ka-GE')));
  });

  constructor() {
    this.categoriesService.list().subscribe({
      next: (categories) => this.categories.set(categories),
    });
    this.articlesService.list({ limit: 1000 }).subscribe({
      next: (articles) => this.linkableArticles.set(articles.filter(isReaderVisibleArticle)),
    });

    this.route.paramMap
      .pipe(
        map((params) => Number(params.get('id'))),
        switchMap((id) => {
          this.loading.set(true);
          this.notFound.set(false);
          return this.articlesService.get(id).pipe(map((article) => ({ id, article })));
        }),
      )
      .subscribe({
        next: ({ id, article }) => {
          this.article.set(article);
          this.relatedArticles.set([]);
          this.loading.set(false);
          this.articlesService.logView(id);
          this.articlesService.related(id).subscribe({
            next: (related) => this.relatedArticles.set(related),
          });
        },
        error: (failure: { status?: number }) => {
          // Every failure used to land on "not found", so a 500 or a dropped
          // connection told the operator the material did not exist.
          if (failure?.status === 404) {
            this.notFound.set(true);
          } else {
            this.loadError.set(true);
          }
          this.loading.set(false);
        },
      });
  }

  goBack(): void {
    const returnUrl = this.returnUrl();
    if (returnUrl) {
      this.router.navigateByUrl(returnUrl);
      return;
    }
    this.location.back();
  }

  protected relatedCategoryName(item: RelatedArticle): string {
    return (
      this.categories().find((category) => category.id === item.category_id)?.name ??
      this.translate.instant('articles.card.uncategorized')
    );
  }

  protected onArticleContentClick(event: MouseEvent): void {
    const target = event.target;
    if (!(target instanceof Element)) {
      return;
    }

    const link = target.closest<HTMLAnchorElement>('a.article-reader__portal-link');
    const href = link?.getAttribute('href');
    if (!href?.startsWith('/')) {
      return;
    }

    event.preventDefault();
    this.router.navigateByUrl(href);
  }

  protected toggleHistory(): void {
    this.showHistory.update((current) => !current);
  }
  /** A hard reload rather than re-running the stream: these pages load once
   *  from a route parameter that will not emit again for the same id. */
  retry(): void {
    window.location.reload();
  }

}
