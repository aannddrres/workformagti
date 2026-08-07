import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { NewsService } from '../../core/services/news.service';
import { NewsSummary } from '../../core/models/news';
import { formatKaDate } from '../../shared/ka-date';

/** Port of renderNews (app-renderers.js:6-44) -- the dashboard's news
 *  preview, distinct from the full paginated /news page (not yet built). */
@Component({
  selector: 'app-news-preview',
  standalone: true,
  imports: [RouterLink, TranslatePipe],
  templateUrl: './news-preview.html'
})
export class NewsPreview {
  private readonly newsService = inject(NewsService);
  private readonly translate = inject(TranslateService);

  protected readonly items = signal<NewsSummary[] | null>(null);
  protected readonly errorMessage = signal<string | null>(null);

  constructor() {
    this.newsService.list({ limit: 20 }).subscribe({
      next: (items) => this.items.set(items),
      error: () => this.errorMessage.set(this.translate.instant('dashboard.news_preview.load_error'))
    });
  }

  dateLabel(item: NewsSummary): string {
    return formatKaDate(item.created_at);
  }
}
