import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ComplianceService } from '../../core/services/compliance.service';
import { MyReading } from '../../core/models/compliance';
import { formatKaDate } from '../../shared/ka-date';
import { detailRouteFor, iconForContentType } from '../../shared/content-type-visuals';
import { FavoriteStar } from '../../shared/favorite-star/favorite-star';

type FilterMode = 'all' | 'unread' | 'read';

/**
 * Port of page-reading (base-layout.html:865-896) + filterReadings/
 * renderFilteredReadings/openReadingItem/markAsRead (app-core.js:1253-1409,
 * app-renderers.js:182-260). The original couples "open" and "mark as read"
 * into one content modal with an appended confirm button; this port uses the
 * already-built routed detail pages (article/news/video) instead of modals
 * (same deviation as Slices 3/4), so a row click navigates to view the item
 * and a separate button marks it read. The original's renderFilteredReadings
 * also creates a #news-load-more-btn element -- a copy-paste/id-collision
 * bug from News (confirmed dead, see news-page.ts's own docstring) -- and
 * the my-readings API has no pagination at all, so no "load more" is built
 * here.
 *
 * Quiz-gated articles: POST mark-read returns 403 when the underlying
 * article has quiz_enabled and the user hasn't passed it yet. No quiz-taking
 * UI exists in Angular yet (deferred, tracked in the migration doc), so this
 * case surfaces an inline "not available yet" message instead of crashing or
 * silently failing -- there are zero required_readings rows in Oracle today,
 * so this path currently has no live impact.
 */
@Component({
  selector: 'app-my-readings-page',
  standalone: true,
  imports: [TranslatePipe, FavoriteStar],
  templateUrl: './my-readings-page.html'
})
export class MyReadingsPage {
  private readonly complianceService = inject(ComplianceService);
  private readonly translate = inject(TranslateService);
  private readonly router = inject(Router);

  protected readonly readings = signal<MyReading[]>([]);
  protected readonly loading = signal(true);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly filter = signal<FilterMode>('all');
  protected readonly markingId = signal<number | null>(null);
  protected readonly quizGateItem = signal<MyReading | null>(null);
  protected readonly markError = signal(false);

  protected readonly filteredReadings = computed(() => {
    const filter = this.filter();
    return this.readings().filter((item) => {
      if (filter === 'all') return true;
      if (filter === 'read') return item.status === 'read';
      return item.status !== 'read';
    });
  });

  constructor() {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.errorMessage.set(null);
    this.complianceService.myReadings().subscribe({
      next: (readings) => {
        this.readings.set(readings);
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set(this.translate.instant('readings.page.load_error'));
        this.loading.set(false);
      }
    });
  }

  setFilter(mode: FilterMode): void {
    this.filter.set(mode);
  }

  iconFor(item: MyReading): string {
    return iconForContentType(item.reading.item_type);
  }

  titleFor(item: MyReading): string {
    return item.item_title || this.translate.instant('readings.page.item_fallback_title', { id: item.reading.item_id });
  }

  dateLabel(item: MyReading): string {
    return item.reading.due_date ? formatKaDate(item.reading.due_date) : '—';
  }

  isOverdue(item: MyReading): boolean {
    return item.status !== 'read' && (item.status === 'overdue' || item.is_overdue);
  }

  view(item: MyReading): void {
    const route = detailRouteFor(item.reading.item_type, item.reading.item_id);
    if (route) {
      this.router.navigate(route);
    }
  }

  markRead(item: MyReading, event: Event): void {
    event.stopPropagation();
    this.markingId.set(item.reading.id);
    this.markError.set(false);
    this.quizGateItem.set(null);
    this.complianceService.markRead(item.reading.id).subscribe((result) => {
      this.markingId.set(null);
      if (result.ok) {
        this.readings.set(
          this.readings().map((r) =>
            r.reading.id === item.reading.id
              ? { ...r, status: 'read', read_at: result.status.read_at, is_overdue: false }
              : r
          )
        );
      } else if (result.quizRequired) {
        this.quizGateItem.set(item);
      } else {
        this.markError.set(true);
      }
    });
  }

  dismissQuizGate(): void {
    this.quizGateItem.set(null);
  }
}
