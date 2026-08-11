import { Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { NewsService } from '../../../core/services/news.service';
import { NewsSummary } from '../../../core/models/news';
import { NewsEditDrawer } from '../news-edit-drawer/news-edit-drawer';

/**
 * Port of #admin-news-table-container (base-layout.html:1853-1870) +
 * fetchAndRenderAdminNews. Plain list, no filters/pagination (matches
 * Python exactly) -- edit + delete row actions ("ისტორია" deferred, same
 * user sign-off as the Articles tab).
 */
@Component({
  selector: 'app-news-admin-table',
  standalone: true,
  imports: [TranslatePipe, DatePipe, NewsEditDrawer],
  templateUrl: './news-admin-table.html'
})
export class NewsAdminTable {
  private readonly newsService = inject(NewsService);
  private readonly translate = inject(TranslateService);

  protected readonly items = signal<NewsSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly editingNews = signal<NewsSummary | { id: null } | null>(null);

  constructor() {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.newsService.listAdmin().subscribe({
      next: (data) => {
        this.items.set(data);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  protected departmentLabel(dept: string): string {
    return dept === 'All' ? this.translate.instant('content.all_departments') : dept;
  }

  protected openCreate(): void {
    this.editingNews.set({ id: null });
  }

  protected openEdit(item: NewsSummary): void {
    this.editingNews.set(item);
  }

  protected closeDrawer(): void {
    this.editingNews.set(null);
  }

  protected onSaved(): void {
    this.closeDrawer();
    this.load();
  }

  protected remove(item: NewsSummary): void {
    if (!window.confirm(this.translate.instant('content.news.confirm_delete'))) return;
    this.newsService.remove(item.id).subscribe({ next: () => this.load(), error: () => {} });
  }
}
