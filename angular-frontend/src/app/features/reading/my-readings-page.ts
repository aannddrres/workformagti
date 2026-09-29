import { Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ComplianceService } from '../../core/services/compliance.service';
import { MyReading } from '../../core/models/compliance';
import { formatKaDate } from '../../shared/ka-date';
import { detailRouteFor, iconForContentType } from '../../shared/content-type-visuals';
import { FavoriteStar } from '../../shared/favorite-star/favorite-star';

type FilterMode = 'all' | 'unread' | 'read';

function isOverdueReading(item: MyReading): boolean {
  return item.status !== 'read' && (item.status === 'overdue' || item.is_overdue);
}

function rank(item: MyReading): number {
  if (isOverdueReading(item)) return 0;
  return item.status === 'read' ? 2 : 1;
}

function time(value: string | null | undefined): number | null {
  const parsed = value ? new Date(value).getTime() : NaN;
  return Number.isNaN(parsed) ? null : parsed;
}

/**
 * Overdue first, then what is still to read by the nearest deadline, then
 * what has been read, newest first (owner decision კ15). The list came in the
 * server's order, so an overdue obligation could sit under three items the
 * operator had already finished.
 */
export function orderReadings(readings: MyReading[]): MyReading[] {
  return [...readings].sort((a, b) => {
    const byRank = rank(a) - rank(b);
    if (byRank !== 0) return byRank;
    if (rank(a) === 2) {
      return (time(b.read_at) ?? 0) - (time(a.read_at) ?? 0);
    }
    const dueA = time(a.reading.due_date);
    const dueB = time(b.reading.due_date);
    if (dueA === null || dueB === null) return dueA === null ? (dueB === null ? 0 : 1) : -1;
    return dueA - dueB;
  });
}

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
 * article has quiz_enabled and the user hasn't passed it yet. Opens
 * {@link QuizTakerModal} in that case (port of app-core.js's
 * openQuizModal(articleId, onSuccess) call site at app-core.js:1369-1374);
 * a passed quiz re-issues the same mark-read call, same as the original's
 * `onSuccess` callback.
 */
@Component({
  selector: 'app-my-readings-page',
  standalone: true,
  imports: [TranslatePipe, FavoriteStar, RouterLink],
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

  protected readonly filteredReadings = computed(() => {
    const filter = this.filter();
    return orderReadings(this.readings()).filter((item) => {
      if (filter === 'all') return true;
      if (filter === 'read') return item.status === 'read';
      return item.status !== 'read';
    });
  });

  constructor() {
    this.load();
  }

  protected load(): void {
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

  /** A read item says when it was read; its deadline no longer matters. */
  dateLine(item: MyReading): string {
    if (item.status === 'read' && item.read_at) {
      return this.translate.instant('readings.page.read_label', { date: formatKaDate(item.read_at) });
    }
    const due = item.reading.due_date ? formatKaDate(item.reading.due_date) : '—';
    return this.translate.instant('readings.page.due_label', { date: due });
  }

  isOverdue(item: MyReading): boolean {
    return isOverdueReading(item);
  }

  /**
   * The row is a link, not a `div role="button"`. It was the latter, which cost
   * the operator middle-click, open-in-new-tab, the status-bar preview of where
   * the row goes, and Space as well as Enter -- on the one screen where opening
   * material is the entire job.
   */
  routeFor(item: MyReading): string[] | null {
    return detailRouteFor(item.reading.item_type, item.reading.item_id);
  }

}
