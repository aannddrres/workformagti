import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { TranslateService } from '@ngx-translate/core';
import { vi } from 'vitest';
import { AdminStatsPage } from './admin-stats-page';
import { AuthService } from '../../core/auth/auth.service';
import { UserProfileService } from '../../core/auth/user-profile.service';
import { StatsService } from '../../core/services/stats.service';
import { CategoriesService } from '../../core/services/categories.service';
import { ArticlesService } from '../../core/services/articles.service';
import { AuditService } from '../../core/services/audit.service';
import { ThemeService } from '../../core/theme/theme.service';

describe('AdminStatsPage capability isolation', () => {
  function createPage(role: string, permissions: string[]) {
    TestBed.resetTestingModule();
    const stats = {
      kpi: vi.fn(() => of({ users: 0, articles: 0, required_readings: 0, videos: 0 })),
      activity: vi.fn(() => of([])),
      compliance: vi.fn(() => of(null)),
      popularSearches: vi.fn(() => of([])),
      failedSearches: vi.fn(() => of([])),
      criticalOperators: vi.fn(() => of({ users: [], total: 0 })),
      userProgress: vi.fn(() => of([]))
    };
    const categories = { listAdmin: vi.fn(() => of([])) };
    const articles = { list: vi.fn(() => of([])) };
    const audit = {
      chainHealth: vi.fn(() => of({ status: 'ok', checked: 0 })),
      list: vi.fn(() => of({ rows: [], total: 0 }))
    };
    const bypass = role === 'admin';

    TestBed.configureTestingModule({
      imports: [AdminStatsPage],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { currentUser: () => ({ role }) } },
        {
          provide: UserProfileService,
          useValue: { hasPermission: (permission: string) => bypass || permissions.includes(permission) }
        },
        { provide: StatsService, useValue: stats },
        { provide: CategoriesService, useValue: categories },
        { provide: ArticlesService, useValue: articles },
        { provide: AuditService, useValue: audit },
        { provide: TranslateService, useValue: { instant: (value: string) => value } }
      ]
    });

    TestBed.createComponent(AdminStatsPage);
    return { stats, categories, articles, audit };
  }

  it('loads only aggregate sources for a stats-only viewer', () => {
    const services = createPage('operator', ['stats.view']);

    expect(services.stats.kpi).toHaveBeenCalledOnce();
    expect(services.stats.activity).toHaveBeenCalledOnce();
    expect(services.stats.compliance).toHaveBeenCalledOnce();
    expect(services.stats.popularSearches).toHaveBeenCalledOnce();
    expect(services.stats.failedSearches).toHaveBeenCalledOnce();
    expect(services.stats.criticalOperators).not.toHaveBeenCalled();
    expect(services.stats.userProgress).not.toHaveBeenCalled();
    expect(services.categories.listAdmin).not.toHaveBeenCalled();
    expect(services.articles.list).not.toHaveBeenCalled();
    expect(services.audit.chainHealth).not.toHaveBeenCalled();
    expect(services.audit.list).not.toHaveBeenCalled();
  });

  it('retains the system-admin operational panels through bypass', () => {
    const services = createPage('admin', []);

    expect(services.stats.criticalOperators).toHaveBeenCalledOnce();
    expect(services.stats.userProgress).toHaveBeenCalledOnce();
    expect(services.categories.listAdmin).toHaveBeenCalledOnce();
    expect(services.articles.list).toHaveBeenCalledOnce();
    expect(services.audit.chainHealth).toHaveBeenCalledOnce();
    expect(services.audit.list).toHaveBeenCalledOnce();
  });
});

describe('AdminStatsPage chart theme', () => {
  // Canvas cannot be drawn here, so the doughnut's renderer is stubbed and
  // what it is asked to draw is checked instead.
  type ChartRenderers = { renderComplianceChart: (...args: unknown[]) => void };

  it('redraws the compliance doughnut with a dark track when the theme flips', () => {
    TestBed.resetTestingModule();
    const isDark = signal(false);
    const render = vi
      .spyOn(AdminStatsPage.prototype as unknown as ChartRenderers, 'renderComplianceChart')
      .mockImplementation(() => undefined);
    TestBed.configureTestingModule({
      imports: [AdminStatsPage],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { currentUser: () => ({ role: 'operator' }) } },
        { provide: UserProfileService, useValue: { hasPermission: () => false } },
        {
          provide: StatsService,
          useValue: {
            kpi: () => of({ users: 0, articles: 0, required_readings: 0, videos: 0 }),
            activity: () => of([]),
            compliance: () => of({ read_percentage: 50, unread_percentage: 50, top_articles: [] }),
            popularSearches: () => of([]),
            failedSearches: () => of([])
          }
        },
        { provide: CategoriesService, useValue: {} },
        { provide: ArticlesService, useValue: {} },
        { provide: AuditService, useValue: {} },
        { provide: TranslateService, useValue: { instant: (value: string) => value } },
        { provide: ThemeService, useValue: { isDark } }
      ]
    });

    const fixture = TestBed.createComponent(AdminStatsPage);
    fixture.detectChanges();
    expect(render).toHaveBeenCalledTimes(1);
    expect(render.mock.lastCall?.[2]).toMatchObject({ track: '#E5E7EB' });

    isDark.set(true);
    fixture.detectChanges();
    expect(render).toHaveBeenCalledTimes(2);
    expect(render.mock.lastCall?.[2]).toMatchObject({ track: '#334155', text: '#94A3B8' });

    render.mockRestore();
  });
});
