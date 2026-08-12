import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Location } from '@angular/common';
import { map, switchMap } from 'rxjs';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ArticlesService } from '../../core/services/articles.service';
import { Article } from '../../core/models/article';
import { formatArticleContent } from '../../shared/format-article-content';
import { formatKaDate } from '../../shared/ka-date';
import { ArticleVersionHistoryOverlay } from './article-version-history-overlay/article-version-history-overlay';

@Component({
  selector: 'app-article-detail-page',
  standalone: true,
  imports: [TranslatePipe, ArticleVersionHistoryOverlay],
  templateUrl: './article-detail-page.html'
})
export class ArticleDetailPage {
  private readonly route = inject(ActivatedRoute);
  private readonly location = inject(Location);
  private readonly articlesService = inject(ArticlesService);
  private readonly translate = inject(TranslateService);

  protected readonly loading = signal(true);
  protected readonly notFound = signal(false);
  protected readonly article = signal<Article | null>(null);
  protected readonly showHistory = signal(false);

  protected readonly formattedContent = computed(() => formatArticleContent(this.article()?.content));

  protected readonly metaLine = computed(() => {
    const a = this.article();
    if (!a) {
      return '';
    }
    const dept = a.target_departments.join(', ');
    const date = formatKaDate(a.created_at);
    const version = this.translate.instant('articles.detail_page.version_label', { version: a.version || 1 });
    const parts = [dept, version, date].filter((p) => p);
    return parts.join(' · ') + (a.tags ? ' · ' + a.tags : '');
  });

  constructor() {
    this.route.paramMap
      .pipe(
        map((params) => Number(params.get('id'))),
        switchMap((id) => {
          this.loading.set(true);
          this.notFound.set(false);
          return this.articlesService.get(id).pipe(map((article) => ({ id, article })));
        })
      )
      .subscribe({
        next: ({ id, article }) => {
          this.article.set(article);
          this.loading.set(false);
          this.articlesService.logView(id);
        },
        error: () => {
          this.notFound.set(true);
          this.loading.set(false);
        }
      });
  }

  goBack(): void {
    this.location.back();
  }

  protected toggleHistory(): void {
    this.showHistory.update((current) => !current);
  }
}
