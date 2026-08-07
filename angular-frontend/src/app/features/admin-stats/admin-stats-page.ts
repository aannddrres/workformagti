import { Component, ElementRef, computed, effect, inject, signal, viewChild } from '@angular/core';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { RouterLink } from '@angular/router';
import Chart from 'chart.js/auto';
import { AuthService } from '../../core/auth/auth.service';
import { StatsService } from '../../core/services/stats.service';
import { ActivityPoint, ComplianceStats, KpiCounts, PopularSearch, TopArticle, UserProgressItem } from '../../core/models/stats';

type ProgressSort = 'perf_desc' | 'perf_asc' | 'name';

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
  imports: [TranslatePipe, RouterLink],
  templateUrl: './admin-stats-page.html'
})
export class AdminStatsPage {
  private readonly statsService = inject(StatsService);
  private readonly translate = inject(TranslateService);
  private readonly authService = inject(AuthService);

  protected readonly isSystemAdmin = computed(() => this.authService.currentUser()?.role === 'admin');

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

  constructor() {
    this.loadAll();

    effect(() => {
      const canvas = this.activityCanvas();
      const points = this.activityPoints();
      if (canvas && points.length) {
        this.renderActivityChart(canvas.nativeElement, points);
      }
    });

    effect(() => {
      const canvas = this.complianceCanvas();
      const data = this.compliance();
      if (canvas && data) {
        this.renderComplianceChart(canvas.nativeElement, data);
      }
    });

    effect(() => {
      const canvas = this.top5Canvas();
      const data = this.compliance();
      if (canvas && data) {
        this.renderTop5Chart(canvas.nativeElement, data.top_articles);
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
    if (this.isSystemAdmin()) {
      this.loadProgress();
    }
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
    if (pct >= 100) return 'text-green-600 dark:text-green-400';
    if (pct > 50) return 'text-yellow-600 dark:text-yellow-400';
    return 'text-red-600 dark:text-red-400';
  }

  private renderActivityChart(canvas: HTMLCanvasElement, points: ActivityPoint[]): void {
    this.activityChart?.destroy();
    this.activityChart = new Chart(canvas, {
      type: 'line',
      data: {
        labels: points.map((p) => p.date),
        datasets: [{
          label: this.translate.instant('stats.admin_page.activity_heading'),
          data: points.map((p) => p.count),
          borderColor: '#B91C1C',
          backgroundColor: 'rgba(185, 28, 28, 0.12)',
          tension: 0.3,
          fill: true,
          pointRadius: 2
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: { y: { beginAtZero: true, ticks: { precision: 0 } } }
      }
    });
  }

  private renderComplianceChart(canvas: HTMLCanvasElement, data: ComplianceStats): void {
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
          backgroundColor: ['#10B981', '#E5E7EB'],
          borderWidth: 0
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: '70%',
        plugins: { legend: { position: 'bottom' } }
      }
    });
  }

  private renderTop5Chart(canvas: HTMLCanvasElement, articles: TopArticle[]): void {
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
          backgroundColor: '#B91C1C',
          borderRadius: 4
        }]
      },
      options: {
        indexAxis: 'y',
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: { x: { beginAtZero: true, ticks: { precision: 0 } } }
      }
    });
  }
}
