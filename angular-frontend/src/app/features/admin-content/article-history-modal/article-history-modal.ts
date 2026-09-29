import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ArticlesService } from '../../../core/services/articles.service';
import { ArticleDiff, ArticleHistorySummaryItem } from '../../../core/models/article-history';
import { PortalDialog } from '../../../shared/portal-dialog/portal-dialog';
import { ConfirmService } from '../../../core/notifications/confirm.service';
import { ToastService } from '../../../core/notifications/toast.service';
import { KaDatePipe } from '../../../shared/ka-date.pipe';

/**
 * Port of the admin-only "ისტორია" action (frontend_api.js:615) +
 * viewArticleHistory/quickLookDiff/restoreArticleVersion
 * (app-core.js:3511-3651). Distinct from the reader-facing "ვერსიების
 * ისტორია" overlay on the article detail page (`modal-history-*`,
 * GET .../versions, predecessor-aware compare, no restore) -- this one is
 * admin-only, reads the CLOB-free GET .../history-summary list, fetches one
 * full revision on expansion, and can restore.
 *
 * <p>Python stacks two separate DOM modals (`#history-modal` +
 * `#diff-modal`); folded into one component with an internal diff view
 * instead, since Angular has no reason to reproduce that DOM-injection
 * detail -- same functional flow (list -> quick compare vs current ->
 * back), one fewer moving part.
 */
@Component({
  selector: 'app-article-history-modal',
  standalone: true,
  imports: [TranslatePipe, KaDatePipe, PortalDialog],
  templateUrl: './article-history-modal.html'
})
export class ArticleHistoryModal {
  private readonly confirmService = inject(ConfirmService);
  private readonly toastService = inject(ToastService);
  private readonly articlesService = inject(ArticlesService);
  private readonly translate = inject(TranslateService);

  readonly articleId = input.required<number>();
  readonly closed = output<void>();
  readonly restored = output<void>();

  protected readonly items = signal<ArticleHistorySummaryItem[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal(false);
  protected readonly expandedId = signal<number | null>(null);
  protected readonly expandedContent = signal<string | null>(null);
  protected readonly expandedLoading = signal(false);
  protected readonly expandedError = signal(false);

  protected readonly diffFor = signal<number | null>(null);
  protected readonly diff = signal<ArticleDiff | null>(null);
  protected readonly diffLoading = signal(false);
  protected readonly diffError = signal(false);

  protected readonly restoringId = signal<number | null>(null);

  /** Ascending chronological order, matching Python's explicit re-sort of
   *  the (server-returned-descending) history list before rendering. */
  protected readonly sortedItems = computed(() =>
    [...this.items()].sort((a, b) => new Date(a.updated_at).getTime() - new Date(b.updated_at).getTime())
  );

  constructor() {
    // Required signal inputs aren't readable synchronously in the
    // constructor body (NG0950) -- effect() defers this read to after
    // Angular has set it, same pattern as ArticleEditDrawer.
    effect(() => {
      this.load(this.articleId());
    });
  }

  private load(articleId: number): void {
    this.loading.set(true);
    this.error.set(false);
    this.articlesService.historySummary(articleId).subscribe({
      next: (data) => {
        this.items.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      }
    });
  }

  protected versionLabel(item: ArticleHistorySummaryItem, index: number): string {
    return 'V' + (item.version_id ?? index + 1);
  }

  protected toggleExpanded(item: ArticleHistorySummaryItem): void {
    if (this.expandedId() === item.id) {
      this.expandedId.set(null);
      this.expandedContent.set(null);
      this.expandedLoading.set(false);
      this.expandedError.set(false);
      return;
    }

    this.expandedId.set(item.id);
    this.expandedContent.set(null);
    this.expandedLoading.set(true);
    this.expandedError.set(false);
    this.articlesService.historyItem(this.articleId(), item.id).subscribe({
      next: (detail) => {
        // Ignore an older request if the user selected another row meanwhile.
        if (this.expandedId() === item.id) {
          this.expandedContent.set(detail.content);
          this.expandedLoading.set(false);
        }
      },
      error: () => {
        if (this.expandedId() === item.id) {
          this.expandedError.set(true);
          this.expandedLoading.set(false);
        }
      }
    });
  }

  protected close(): void {
    this.closed.emit();
  }

  protected showDiff(item: ArticleHistorySummaryItem): void {
    this.diffFor.set(item.id);
    this.diff.set(null);
    this.diffLoading.set(true);
    this.diffError.set(false);
    this.articlesService.diff(this.articleId(), item.id).subscribe({
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

  protected closeDiff(): void {
    this.diffFor.set(null);
    this.diff.set(null);
  }

  protected async restore(item: ArticleHistorySummaryItem): Promise<void> {
    if (!(await this.confirmService.ask({
      message: this.translate.instant('content.history.confirm_restore'),
      confirmLabel: this.translate.instant('content.history.restore')
    }))) {
      return;
    }
    this.restoringId.set(item.id);
    this.articlesService.restoreVersion(this.articleId(), item.id).subscribe({
      next: () => {
        this.restoringId.set(null);
        this.restored.emit();
      },
      error: () => {
        this.restoringId.set(null);
        this.toastService.error(this.translate.instant('content.history.restore_failed'));
      }
    });
  }
}
