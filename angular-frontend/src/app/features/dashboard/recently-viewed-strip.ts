import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ArticlesService } from '../../core/services/articles.service';
import { RecentlyViewedItem } from '../../core/models/article';
import { formatKaDate } from '../../shared/ka-date';

/** Port of fetchAndRenderDashboardRecentlyViewed (frontend_api.js:501-539)
 *  -- server-backed (GET /api/me/recently-viewed), cross-device. Distinct
 *  from the KB page's separate localStorage-only recently-viewed strip. */
@Component({
  selector: 'app-recently-viewed-strip',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './recently-viewed-strip.html'
})
export class RecentlyViewedStrip {
  private readonly articlesService = inject(ArticlesService);
  private readonly translate = inject(TranslateService);
  private readonly router = inject(Router);

  protected readonly items = signal<RecentlyViewedItem[] | null>(null);
  protected readonly errorMessage = signal<string | null>(null);

  constructor() {
    this.articlesService.recentlyViewed().subscribe({
      next: (items) => this.items.set(items),
      error: () => this.errorMessage.set(this.translate.instant('dashboard.recently_viewed.load_error'))
    });
  }

  dateLabel(item: RecentlyViewedItem): string {
    return formatKaDate(item.viewed_at);
  }

  open(articleId: number): void {
    this.router.navigate(['/article', articleId]);
  }
}
