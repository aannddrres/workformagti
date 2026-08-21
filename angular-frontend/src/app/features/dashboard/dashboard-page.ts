import { Component, computed, inject, signal } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { CategoriesService } from '../../core/services/categories.service';
import { ArticlesService } from '../../core/services/articles.service';
import { ComplianceService } from '../../core/services/compliance.service';
import { AuthService } from '../../core/auth/auth.service';
import { Category } from '../../core/models/category';
import { ArticleSummary } from '../../core/models/article';
import { MyProgress } from '../../core/models/compliance';
import { CategoryTile } from '../../shared/category-tile/category-tile';
import { ProgressRing } from '../../shared/progress-ring/progress-ring';
import { MandatoryReadingWidget } from './mandatory-reading-widget';
import { NewsPreview } from './news-preview';
import { RecentlyViewedStrip } from './recently-viewed-strip';
import { isRecentlyPublished } from '../../shared/category-visuals';
import { buildRecursiveCategoryCounts, descendantCategoryIds } from '../../shared/category-tree';
import { isReaderVisibleArticle } from '../../shared/article-visibility';

const MANAGEMENT_ROLES = ['admin', 'content_admin', 'manager'];

/**
 * Port of page-dashboard (base-layout.html:501-739). The original is a
 * single unguarded fetch pile fired from DOMContentLoaded; here each widget
 * is its own self-fetching component so one failing request can't affect
 * the others (already true in the original) while giving every widget a
 * consistent, visible error state (the original left 4 of 5 widgets stuck
 * on their loading skeleton on failure -- only "recently viewed" showed a
 * real error message. See migration doc for the full slice-2 writeup).
 */
@Component({
  selector: 'app-dashboard-page',
  standalone: true,
  imports: [
    CategoryTile,
    ProgressRing,
    MandatoryReadingWidget,
    NewsPreview,
    RecentlyViewedStrip,
    TranslatePipe,
  ],
  templateUrl: './dashboard-page.html',
})
export class DashboardPage {
  private readonly categoriesService = inject(CategoriesService);
  private readonly articlesService = inject(ArticlesService);
  private readonly complianceService = inject(ComplianceService);
  private readonly authService = inject(AuthService);

  protected readonly isManagement = computed(() =>
    MANAGEMENT_ROLES.includes(this.authService.currentUser()?.role ?? ''),
  );

  // Show historical categories even when they predate mandatory slugs;
  // CategoryTile falls back to the already-supported numeric ID route.
  protected readonly categories = signal<Category[]>([]);
  protected readonly gridCategories = computed(() =>
    this.categories().filter(
      (category) =>
        category.parent_id == null &&
        category.is_active &&
        (this.categoryCounts().get(category.id) ?? 0) > 0,
    ),
  );

  protected readonly countingSet = signal<ArticleSummary[]>([]);
  protected readonly categoryCounts = computed(() =>
    buildRecursiveCategoryCounts(this.categories(), this.countingSet()),
  );

  protected readonly progress = signal<MyProgress | null>(null);
  protected readonly progressError = signal(false);

  /**
   * Drives which half of the page leads: the operator's outstanding
   * obligations, or the decorative banner. Management roles have no readings
   * by design, so they always get the banner.
   */
  protected readonly needsAttention = computed(
    () => !this.isManagement() && (this.progress()?.pending ?? 0) > 0,
  );

  /**
   * True while an operator's progress is still unknown.
   *
   * Without this the page would render the banner first and then swap it for
   * the alarm block a moment later, every single load — the layout jumping
   * under the reader exactly where the most urgent content goes. Holding the
   * slot until the answer arrives costs one skeleton and avoids the flip.
   */
  protected readonly awaitingProgress = computed(
    () => !this.isManagement() && this.progress() === null && !this.progressError(),
  );

  constructor() {
    this.categoriesService.list().subscribe((categories) => this.categories.set(categories));
    this.articlesService
      .list({ limit: 1000 })
      .subscribe((articles) => this.countingSet.set(articles.filter(isReaderVisibleArticle)));

    if (!this.isManagement()) {
      this.complianceService.myProgress().subscribe({
        next: (p) => this.progress.set(p),
        error: () => this.progressError.set(true),
      });
    }
  }

  hasRecentInCategory(category: Category): boolean {
    const ids = descendantCategoryIds(this.categories(), category.id);
    return this.countingSet().some(
      (article) =>
        article.category_id != null &&
        ids.has(article.category_id) &&
        isRecentlyPublished({ publishedAt: article.published_at, createdAt: article.created_at }),
    );
  }
}
