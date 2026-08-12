import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { TranslatePipe } from '@ngx-translate/core';
import { ArticlesService } from '../../../core/services/articles.service';
import { ArticleDiff, ArticleVersionItem } from '../../../core/models/article-history';

/**
 * Reader-facing "ვერსიების ისტორია" overlay -- port of app-core.js's
 * modal-history-* DOM (toggleModalHistoryOverlay/loadModalHistoryList/
 * loadModalHistoryDiff, app-core.js:6507-6780). Distinct from
 * {@link ../../admin-content/article-history-modal/article-history-modal!ArticleHistoryModal},
 * which is admin-only, reads the raw GET .../history list, and can
 * restore: this one is available to every role with article read access
 * (GET .../versions + predecessor-aware GET .../diff), has no restore
 * action, and defaults each selection to a compare-against-predecessor
 * diff instead of always-vs-current.
 */
@Component({
  selector: 'app-article-version-history-overlay',
  standalone: true,
  imports: [TranslatePipe, DatePipe],
  templateUrl: './article-version-history-overlay.html'
})
export class ArticleVersionHistoryOverlay {
  private readonly articlesService = inject(ArticlesService);

  readonly articleId = input.required<number>();
  readonly closed = output<void>();

  protected readonly versions = signal<ArticleVersionItem[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal(false);

  protected readonly selected = signal<ArticleVersionItem | null>(null);
  protected readonly compareId = signal<number | null>(null);

  protected readonly diff = signal<ArticleDiff | null>(null);
  protected readonly diffLoading = signal(false);
  protected readonly diffError = signal(false);

  /** Predecessor of the currently selected version -- the immediate lower
   *  version number in the (already server-sorted-descending) list. Used
   *  only to preselect the compare dropdown; the diff endpoint computes
   *  its own predecessor server-side when compareId is left unset. */
  protected readonly predecessor = computed<ArticleVersionItem | null>(() => {
    const primary = this.selected();
    if (!primary) {
      return null;
    }
    return (
      this.versions()
        .filter((v) => v.history_id !== primary.history_id && v.version < primary.version)
        .sort((a, b) => b.version - a.version)[0] ?? null
    );
  });

  protected readonly compareOptions = computed(() =>
    this.versions().filter((v) => v.history_id !== this.selected()?.history_id)
  );

  constructor() {
    // Required signal inputs aren't readable synchronously in the
    // constructor body (NG0950) -- effect() defers this read, same
    // pattern as ArticleHistoryModal/ArticleEditDrawer.
    effect(() => {
      this.load(this.articleId());
    });
  }

  private load(articleId: number): void {
    this.loading.set(true);
    this.error.set(false);
    this.articlesService.versions(articleId).subscribe({
      next: (data) => {
        this.versions.set(data);
        this.loading.set(false);
        if (data.length > 0) {
          this.selectVersion(data[0]);
        }
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      }
    });
  }

  private static readonly ROW_BASE =
    'my-1.5 flex cursor-pointer flex-col gap-1.5 rounded-xl border p-3 transition-all';
  private static readonly ROW_ACTIVE = 'bg-red-50 border-red-100 dark:bg-red-950/40 dark:border-red-900/60';
  private static readonly ROW_INACTIVE =
    'border-gray-100 dark:border-zinc-800 hover:bg-gray-50 dark:hover:bg-zinc-800/60';

  protected rowClass(item: ArticleVersionItem): string {
    const active = this.selected()?.history_id === item.history_id;
    return `${ArticleVersionHistoryOverlay.ROW_BASE} ${active ? ArticleVersionHistoryOverlay.ROW_ACTIVE : ArticleVersionHistoryOverlay.ROW_INACTIVE}`;
  }

  protected badgeKey(item: ArticleVersionItem, index: number): 'current' | 'original' | 'other' {
    if (index === 0) {
      return 'current';
    }
    if (index === this.versions().length - 1) {
      return 'original';
    }
    return 'other';
  }

  protected selectVersion(item: ArticleVersionItem): void {
    this.selected.set(item);
    this.compareId.set(null);
    this.loadDiff(item.history_id, undefined);
  }

  protected onCompareChange(value: string): void {
    const parsed = value ? Number(value) : null;
    this.compareId.set(parsed);
    const primary = this.selected();
    if (primary) {
      this.loadDiff(primary.history_id, parsed ?? undefined);
    }
  }

  private loadDiff(historyId: number, compareHistoryId: number | undefined): void {
    this.diff.set(null);
    this.diffLoading.set(true);
    this.diffError.set(false);
    this.articlesService.diffVersion(this.articleId(), historyId, compareHistoryId).subscribe({
      next: (data) => {
        this.diff.set(data);
        this.diffLoading.set(false);
      },
      error: () => {
        this.diffError.set(true);
        this.diffLoading.set(false);
      }
    });
  }

  protected close(): void {
    this.closed.emit();
  }
}
