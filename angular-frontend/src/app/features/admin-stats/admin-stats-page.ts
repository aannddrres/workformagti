import { Component, ElementRef, computed, effect, inject, signal, viewChild } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { RouterLink } from '@angular/router';
import Chart from 'chart.js/auto';
import { AuthService } from '../../core/auth/auth.service';
import { StatsService } from '../../core/services/stats.service';
import { ActivityPoint, ComplianceStats, CriticalOperatorsResponse, KpiCounts, PopularSearch, TopArticle, UserProgressItem } from '../../core/models/stats';
import { brandRgb } from '../../core/brand';
import { CategoriesService } from '../../core/services/categories.service';
import { ArticlesService } from '../../core/services/articles.service';
import { AuditService } from '../../core/services/audit.service';
import { Category } from '../../core/models/category';
import { ArticleSummary } from '../../core/models/article';
import { AuditChainHealth, AuditLogEntry } from '../../core/models/audit';
import { formatKaDateTime } from '../../shared/ka-date';
import { formatAuditAction } from '../../shared/audit-format';
import { categoryIconClass } from '../../shared/category-visuals';
import { formatDepartmentLabel } from '../../shared/department-badge';
import { UserProfileService } from '../../core/auth/user-profile.service';
import { ThemeService } from '../../core/theme/theme.service';

type ProgressSort = 'perf_desc' | 'perf_asc' | 'name';

/**
 * Canvas never sees a `dark:` class, so the charts take their ink from here
 * and are redrawn when the theme flips. Light is Chart.js's own defaults
 * (plus the doughnut's grey track), spelled out so a flip can return to them;
 * dark is the page's slate-400 text on the slate-900 card, and a slate-700
 * track where #E5E7EB would be a white ring.
 */
const CHART_INK = {
  light: { text: '#666', grid: 'rgba(0, 0, 0, 0.1)', track: '#E5E7EB' },
  dark: { text: '#94A3B8', grid: 'rgba(148, 163, 184, 0.15)', track: '#334155' }
} as const;
type ChartInk = (typeof CHART_INK)[keyof typeof CHART_INK];

function parsePercentage(label: string): number {
  return parseInt(label.replace('%', ''), 10) || 0;
}

/**
 * Port of page-admin's "მთავარი პანელი" (admin-main) sub-panel --
 * base-layout.html:1488-1687 plus its Chart.js wiring in app-core.js's
 * initCharts() and the fetch helpers in frontend_api.js. Full rewrite, not a
 * line translation, same convention as TeamStatsPage.
 *
 * Deliberately not ported:
 * - The hardcoded (non-data-driven) trend arrows and synthetic progress-bar
 *   max on the KPI cards (fetchKPIs, frontend_api.js:1674-1717) -- these
 *   never reflected anything real; showing them would be actively
 *   misleading rather than a faithful port.
 * - The Stale Content Alert panel and Emergency Broadcast form that share
 *   this page in the Python original -- they belong to the Content and
 *   Messaging domains respectively, not Stats; deferred to those domains'
 *   own admin-tooling slices.
 * - A date-range picker -- the original doesn't have one either (activity
 *   is hardcoded to a 7-day window).
 *
 * One genuine bug fixed here, with the user's explicit sign-off: the top-5
 * bar chart now uses TopArticle.read_count -- a real per-article count the
 * backend already computed but never sent -- instead of Python's synthetic
 * descending placeholder numbers. See TopArticleResponse's javadoc.
 */
@Component({
  selector: 'app-admin-stats-page',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './admin-stats-page.html'
})
export class AdminStatsPage {
  protected readonly departmentLabel = formatDepartmentLabel;
  private readonly statsService = inject(StatsService);
  private readonly translate = inject(TranslateService);
  private readonly authService = inject(AuthService);
  private readonly profiles = inject(UserProfileService);
  private readonly categoriesService = inject(CategoriesService);
  private readonly articlesService = inject(ArticlesService);
  private readonly auditService = inject(AuditService);
  private readonly theme = inject(ThemeService);

  protected readonly isSystemAdmin = computed(() => this.authService.currentUser()?.role === 'admin');
  protected readonly canManageContent = computed(() => this.profiles.hasPermission('content.manage'));

  protected readonly kpi = signal<KpiCounts | null>(null);
  protected readonly kpiLoading = signal(true);
  protected readonly kpiError = signal(false);

  protected readonly activityPoints = signal<ActivityPoint[]>([]);
  protected readonly activityLoading = signal(true);
  protected readonly activityError = signal(false);

  protected readonly compliance = signal<ComplianceStats | null>(null);
  protected readonly complianceLoading = signal(true);
  protected readonly complianceError = signal(false);

  protected readonly popularSearches = signal<PopularSearch[]>([]);
  protected readonly popularLoading = signal(true);
  protected readonly popularError = signal(false);

  protected readonly failedSearches = signal<PopularSearch[]>([]);
  protected readonly failedLoading = signal(true);
  protected readonly failedError = signal(false);
  protected readonly critical = signal<CriticalOperatorsResponse | null>(null);
  protected readonly criticalLoading = signal(false);
  protected readonly categories = signal<Category[]>([]);
  protected readonly articles = signal<ArticleSummary[]>([]);
  protected readonly recentAudit = signal<AuditLogEntry[]>([]);
  protected readonly auditChain = signal<AuditChainHealth | null>(null);
  protected readonly lastUpdated = signal(new Date().toISOString());

  protected readonly failedSearchTotal = computed(() =>
    this.failedSearches().reduce((total, item) => total + item.count, 0)
  );
  protected readonly topCategories = computed(() =>
    this.categories().filter((category) => category.is_active && category.parent_id === null).slice(0, 8)
  );
  protected readonly categoryCounts = computed(() => {
    const counts = new Map<string, number>();
    for (const article of this.articles()) {
      const key = article.category_name ?? '';
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    return counts;
  });

  protected readonly progress = signal<UserProgressItem[]>([]);
  protected readonly progressLoading = signal(false);
  protected readonly progressError = signal(false);
  protected readonly progressDept = signal('');
  protected readonly progressSort = signal<ProgressSort>('perf_desc');
  protected readonly progressIncompleteOnly = signal(false);

  protected readonly progressDepartments = computed(() => {
    const depts = new Set<string>();
    for (const item of this.progress()) {
      if (item.department) {
        depts.add(item.department);
      }
    }
    return [...depts].sort();
  });

  protected readonly filteredProgress = computed(() => {
    let items = this.progress();
    const dept = this.progressDept();
    if (dept) {
      items = items.filter((i) => i.department === dept);
    }
    if (this.progressIncompleteOnly()) {
      items = items.filter((i) => parsePercentage(i.percentage) < 100);
    }
    const sort = this.progressSort();
    if (sort === 'name') {
      items = [...items].sort((a, b) => a.user_name.localeCompare(b.user_name, 'ka'));
    } else if (sort === 'perf_asc') {
      items = [...items].sort((a, b) => parsePercentage(a.percentage) - parsePercentage(b.percentage));
    } else {
      items = [...items].sort((a, b) => parsePercentage(b.percentage) - parsePercentage(a.percentage));
    }
    return items;
  });

  private readonly activityCanvas = viewChild<ElementRef<HTMLCanvasElement>>('activityCanvas');
  private readonly complianceCanvas = viewChild<ElementRef<HTMLCanvasElement>>('complianceCanvas');
  private readonly top5Canvas = viewChild<ElementRef<HTMLCanvasElement>>('top5Canvas');

  private activityChart: Chart | null = null;
  private complianceChart: Chart | null = null;
  private top5Chart: Chart | null = null;

  /** Read inside each chart effect, so the theme toggle redraws all three. */
  private readonly chartInk = computed<ChartInk>(() => (this.theme.isDark() ? CHART_INK.dark : CHART_INK.light));

  constructor() {
    this.loadAll();

    effect(() => {
      const canvas = this.activityCanvas();
      const points = this.activityPoints();
      const ink = this.chartInk();
      if (canvas && points.length) {
        this.renderActivityChart(canvas.nativeElement, points, ink);
      }
    });

    effect(() => {
      const canvas = this.complianceCanvas();
      const data = this.compliance();
      const ink = this.chartInk();
      if (canvas && data) {
        this.renderComplianceChart(canvas.nativeElement, data, ink);
      }
    });

    effect(() => {
      const canvas = this.top5Canvas();
      const data = this.compliance();
      const ink = this.chartInk();
      if (canvas && data) {
        this.renderTop5Chart(canvas.nativeElement, data.top_articles, ink);
      }
    });
  }

  refresh(): void {
    this.loadAll();
  }

  private loadAll(): void {
    this.loadKpi();
    this.loadActivity();
    this.loadCompliance();
    this.loadPopularSearches();
    this.loadFailedSearches();
    if (this.canManageContent()) {
      this.loadCategories();
    }
    if (this.isSystemAdmin()) {
      this.loadAuditSummary();
      this.loadProgress();
      this.loadCriticalOperators();
    }
    this.lastUpdated.set(new Date().toISOString());
  }

  private loadKpi(): void {
    this.kpiLoading.set(true);
    this.kpiError.set(false);
    this.statsService.kpi().subscribe({
      next: (data) => { this.kpi.set(data); this.kpiLoading.set(false); },
      error: () => { this.kpiError.set(true); this.kpiLoading.set(false); }
    });
  }

  private loadActivity(): void {
    this.activityLoading.set(true);
    this.activityError.set(false);
    this.statsService.activity(7, 'day').subscribe({
      next: (data) => { this.activityPoints.set(data); this.activityLoading.set(false); },
      error: () => { this.activityError.set(true); this.activityLoading.set(false); }
    });
  }

  private loadCompliance(): void {
    this.complianceLoading.set(true);
    this.complianceError.set(false);
    this.statsService.compliance().subscribe({
      next: (data) => { this.compliance.set(data); this.complianceLoading.set(false); },
      error: () => { this.complianceError.set(true); this.complianceLoading.set(false); }
    });
  }

  private loadPopularSearches(): void {
    this.popularLoading.set(true);
    this.popularError.set(false);
    this.statsService.popularSearches().subscribe({
      next: (data) => { this.popularSearches.set(data); this.popularLoading.set(false); },
      error: () => { this.popularError.set(true); this.popularLoading.set(false); }
    });
  }

  private loadFailedSearches(): void {
    this.failedLoading.set(true);
    this.failedError.set(false);
    this.statsService.failedSearches().subscribe({
      next: (data) => { this.failedSearches.set(data); this.failedLoading.set(false); },
      error: () => { this.failedError.set(true); this.failedLoading.set(false); }
    });
  }

  private loadCriticalOperators(): void {
    this.criticalLoading.set(true);
    this.statsService.criticalOperators().subscribe({
      next: (data) => {
        this.critical.set(data);
        this.criticalLoading.set(false);
      },
      error: () => this.criticalLoading.set(false)
    });
  }

  private loadCategories(): void {
    this.categoriesService.listAdmin().subscribe({ next: (data) => this.categories.set(data) });
    this.articlesService.list({ limit: 200 }).subscribe({ next: (data) => this.articles.set(data) });
  }

  private loadAuditSummary(): void {
    this.auditService.chainHealth(100).subscribe({ next: (data) => this.auditChain.set(data) });
    this.auditService.list({}, 6, 0).subscribe({ next: (data) => this.recentAudit.set(data.rows) });
  }

  private loadProgress(): void {
    this.progressLoading.set(true);
    this.progressError.set(false);
    this.statsService.userProgress().subscribe({
      next: (data) => { this.progress.set(data); this.progressLoading.set(false); },
      error: () => { this.progressError.set(true); this.progressLoading.set(false); }
    });
  }

  onProgressDeptChange(event: Event): void {
    this.progressDept.set((event.target as HTMLSelectElement).value);
  }

  onProgressSortChange(event: Event): void {
    this.progressSort.set((event.target as HTMLSelectElement).value as ProgressSort);
  }

  toggleIncompleteOnly(): void {
    this.progressIncompleteOnly.set(!this.progressIncompleteOnly());
  }

  pctColorClass(percentageLabel: string): string {
    const pct = parsePercentage(percentageLabel);
    if (pct >= 100) return 'text-green-700 dark:text-green-400';
    if (pct > 50) return 'text-yellow-700 dark:text-yellow-400';
    return 'text-red-700 dark:text-red-400';
  }

  categoryIcon(category: Category): string {
    return categoryIconClass(category, category.name);
  }

  categoryRoute(category: Category): string {
    return `/category/${category.slug || category.id}`;
  }

  kaDateTime(value: string): string {
    return formatKaDateTime(value);
  }

  // The audit page's own labels. This screen used to carry a nine-entry copy,
  // and anything outside it came out as the lowercased code -- "file access".
  actionLabel(action: string): string {
    return formatAuditAction(action, (key) => this.translate.instant(key));
  }

  private renderActivityChart(canvas: HTMLCanvasElement, points: ActivityPoint[], ink: ChartInk): void {
    this.activityChart?.destroy();
    this.activityChart = new Chart(canvas, {
      type: 'line',
      data: {
        labels: points.map((p) => p.date),
        datasets: [{
          label: this.translate.instant('stats.admin_page.activity_heading'),
          data: points.map((p) => p.count),
          borderColor: brandRgb(),
          backgroundColor: brandRgb(0.12),
          tension: 0.3,
          fill: true,
          pointRadius: 2
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: {
          x: { ticks: { color: ink.text }, grid: { color: ink.grid } },
          y: { beginAtZero: true, ticks: { precision: 0, color: ink.text }, grid: { color: ink.grid } }
        }
      }
    });
  }

  private renderComplianceChart(canvas: HTMLCanvasElement, data: ComplianceStats, ink: ChartInk): void {
    this.complianceChart?.destroy();
    this.complianceChart = new Chart(canvas, {
      type: 'doughnut',
      data: {
        labels: [
          this.translate.instant('stats.admin_page.chart_read_label', { value: data.read_percentage }),
          this.translate.instant('stats.admin_page.chart_unread_label', { value: data.unread_percentage })
        ],
        datasets: [{
          data: [data.read_percentage, data.unread_percentage],
          backgroundColor: ['#10B981', ink.track],
          borderWidth: 0
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: '70%',
        plugins: { legend: { position: 'bottom', labels: { color: ink.text } } }
      }
    });
  }

  private renderTop5Chart(canvas: HTMLCanvasElement, articles: TopArticle[], ink: ChartInk): void {
    this.top5Chart?.destroy();
    this.top5Chart = null;
    if (!articles.length) {
      return;
    }
    this.top5Chart = new Chart(canvas, {
      type: 'bar',
      data: {
        labels: articles.map((a) => a.title),
        datasets: [{
          data: articles.map((a) => a.read_count),
          backgroundColor: brandRgb(),
          borderRadius: 4
        }]
      },
      options: {
        indexAxis: 'y',
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: {
          x: { beginAtZero: true, ticks: { precision: 0, color: ink.text }, grid: { color: ink.grid } },
          y: { ticks: { color: ink.text }, grid: { color: ink.grid } }
        }
      }
    });
  }
}
