import { vi } from 'vitest';
import { of, throwError } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';
import { provideTranslateService } from '@ngx-translate/core';
import { ArticleHistoryModal } from './article-history-modal';
import { ArticlesService } from '../../../core/services/articles.service';
import { ArticleHistorySummaryItem } from '../../../core/models/article-history';

const SUMMARY: ArticleHistorySummaryItem = {
  id: 9,
  title: 'ვერსია',
  updated_at: '2026-08-25T12:00:00+04:00',
  author_name: 'რედაქტორი',
  version_id: 2,
};

describe('ArticleHistoryModal CLOB-free list flow', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ArticleHistoryModal],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideTranslateService({ lang: 'ka', fallbackLang: 'ka' }),
        provideTranslateHttpLoader({ prefix: '/i18n/', suffix: '.json' }),
      ],
    }).compileComponents();
  });

  it('loads history metadata from the summary endpoint', () => {
    const fixture = TestBed.createComponent(ArticleHistoryModal);
    const service = TestBed.inject(ArticlesService);
    const summarySpy = vi.spyOn(service, 'historySummary').mockReturnValue(of([SUMMARY]));
    fixture.componentRef.setInput('articleId', 7);

    fixture.detectChanges();

    expect(summarySpy).toHaveBeenCalledWith(7);
    expect((fixture.componentInstance as any).items()).toEqual([SUMMARY]);
  });

  it('loads only the selected revision content when a row is expanded', () => {
    const fixture = TestBed.createComponent(ArticleHistoryModal);
    fixture.componentRef.setInput('articleId', 7);
    const component = fixture.componentInstance as any;
    const service = TestBed.inject(ArticlesService);
    const detailSpy = vi.spyOn(service, 'historyItem').mockReturnValue(of({
      ...SUMMARY,
      content: 'სრული შინაარსი',
    }));

    component.toggleExpanded(SUMMARY);

    expect(detailSpy).toHaveBeenCalledWith(7, 9);
    expect(component.expandedContent()).toBe('სრული შინაარსი');
    expect(component.expandedLoading()).toBe(false);
  });

  it('fails locally when the selected revision detail cannot be loaded', () => {
    const fixture = TestBed.createComponent(ArticleHistoryModal);
    fixture.componentRef.setInput('articleId', 7);
    const component = fixture.componentInstance as any;
    const service = TestBed.inject(ArticlesService);
    vi.spyOn(service, 'historyItem').mockReturnValue(throwError(() => new Error('network')));

    component.toggleExpanded(SUMMARY);

    expect(component.expandedError()).toBe(true);
    expect(component.expandedLoading()).toBe(false);
  });
});
