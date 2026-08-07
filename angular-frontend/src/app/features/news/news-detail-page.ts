import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Location } from '@angular/common';
import { map, switchMap } from 'rxjs';
import { TranslatePipe } from '@ngx-translate/core';
import { NewsService } from '../../core/services/news.service';
import { News } from '../../core/models/news';
import { formatArticleContent } from '../../shared/format-article-content';
import { formatKaDate } from '../../shared/ka-date';
import { getDepartmentBadge } from '../../shared/department-badge';

/**
 * Port of openNewsDetailModal (app-core.js:2108-2173) as a routed page
 * instead of a modal, matching this port's established
 * detail-route-not-modal pattern (ArticleDetailPage/CategoryViewPage).
 * News has no view-tracking of any kind in the original (confirmed --
 * no /api/news/{id}/view route exists), so none is added here either.
 */
@Component({
  selector: 'app-news-detail-page',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './news-detail-page.html'
})
export class NewsDetailPage {
  private readonly route = inject(ActivatedRoute);
  private readonly location = inject(Location);
  private readonly newsService = inject(NewsService);

  protected readonly loading = signal(true);
  protected readonly notFound = signal(false);
  protected readonly item = signal<News | null>(null);

  protected readonly formattedContent = computed(() => formatArticleContent(this.item()?.content));
  protected readonly badge = computed(() => getDepartmentBadge(this.item()?.target_department));
  protected readonly dateLabel = computed(() => (this.item() ? formatKaDate(this.item()!.created_at) : ''));

  constructor() {
    this.route.paramMap
      .pipe(
        map((params) => Number(params.get('id'))),
        switchMap((id) => this.newsService.get(id))
      )
      .subscribe({
        next: (item) => {
          this.item.set(item);
          this.loading.set(false);
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
}
