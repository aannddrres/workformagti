import { Component, computed, inject, signal } from '@angular/core';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { CategoriesService } from '../../core/services/categories.service';
import { ArticlesService } from '../../core/services/articles.service';
import { ComplianceService } from '../../core/services/compliance.service';
import { AuthService } from '../../core/auth/auth.service';
import { Category } from '../../core/models/category';
import { ArticleSummary } from '../../core/models/article';
import { MyProgress } from '../../core/models/compliance';
import { ArticleCardViewModel } from '../../shared/article-card/article-card';
import { CategoryTile } from '../../shared/category-tile/category-tile';
import { ProgressRing } from '../../shared/progress-ring/progress-ring';
import { MandatoryReadingWidget } from './mandatory-reading-widget';
import { NewsPreview } from './news-preview';
import { RecentlyViewedStrip } from './recently-viewed-strip';
import { buildCategoryCounts, isRecentlyPublished } from '../../shared/category-visuals';

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
  imports: [CategoryTile, ProgressRing, MandatoryReadingWidget, NewsPreview, RecentlyViewedStrip, TranslatePipe],
  templateUrl: './dashboard-page.html'
})
export class DashboardPage {
  private readonly categoriesService = inject(CategoriesService);
  private readonly articlesService = inject(ArticlesService);
  private readonly complianceService = inject(ComplianceService);
  private readonly authService = inject(AuthService);
  private readonly translate = inject(TranslateService);

  protected readonly isManagement = computed(() => MANAGEMENT_ROLES.includes(this.authService.currentUser()?.role ?? ''));

  // Dashboard's category grid shows every category with a slug (no
  // top-level-only cap, unlike the KB page's bento grid) -- a real
  // difference in the original (renderDashboardCategoryGrid vs renderKbBento).
  protected readonly categories = signal<Category[]>([]);
  protected readonly gridCategories = computed(() => this.categories().filter((c) => !!c.slug));

  protected readonly countingSet = signal<ArticleCardViewModel[]>([]);
  protected readonly categoryCounts = computed(() => buildCategoryCounts(this.countingSet()));

  protected readonly progress = signal<MyProgress | null>(null);
  protected readonly progressError = signal(false);

  /**
   * Drives which half of the page leads: the operator's outstanding
   * obligations, or the decorative banner. Management roles have no readings
   * by design, so they always get the banner.
   */
  protected readonly needsAttention = computed(
    () => !this.isManagement() && (this.progress()?.pending ?? 0) > 0
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
    () => !this.isManagement() && this.progress() === null && !this.progressError()
  );

  constructor() {
    this.categoriesService.list().subscribe((categories) => this.categories.set(categories));
    this.articlesService
      .list({ limit: 200 })
      .subscribe((articles) => this.countingSet.set(articles.map((a) => this.toCardViewModel(a))));

    if (!this.isManagement()) {
      this.complianceService.myProgress().subscribe({
        next: (p) => this.progress.set(p),
        error: () => this.progressError.set(true)
      });
    }
  }

  private toCardViewModel(a: ArticleSummary): ArticleCardViewModel {
    return {
      id: a.id,
      title: a.title,
      categoryName: a.category_name || this.translate.instant('articles.card.uncategorized'),
      createdAt: a.created_at,
      publishedAt: a.published_at,
      readTime: a.read_time
    };
  }

  hasRecentInCategory(category: Category): boolean {
    return this.countingSet().some((card) => card.categoryName === category.name && isRecentlyPublished(card));
  }
}
