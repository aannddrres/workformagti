import { vi } from 'vitest';
import { of } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';
import { NewsEditDrawer } from './news-edit-drawer';
import { NewsService } from '../../../core/services/news.service';

/** Covers submit()'s due-date guard (news-edit-drawer.ts:133-139). */
describe('NewsEditDrawer due-date validation', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [NewsEditDrawer],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideTranslateService({ lang: 'ka', fallbackLang: 'ka' }),
        provideTranslateHttpLoader({ prefix: '/i18n/', suffix: '.json' })
      ]
    }).compileComponents();
  });

  it('blocks submit and sets dueDateError when mandatory but due date is empty', () => {
    const fixture = TestBed.createComponent(NewsEditDrawer);
    fixture.componentRef.setInput('newsId', null);
    const component = fixture.componentInstance as any;
    // The required fields, filled: the form is novalidate and says so itself now.
    component.title.set('სათაური');
    component.content.set('ტექსტი');

    const newsService = TestBed.inject(NewsService);
    const createSpy = vi.spyOn(newsService, 'createCommand');
    const updateSpy = vi.spyOn(newsService, 'updateCommand');

    component.isMandatory.set(true);
    component.dueDate.set('');

    component.submit({ preventDefault: () => {} } as Event);

    expect(component.dueDateError()).toBe(true);
    expect(createSpy).not.toHaveBeenCalled();
    expect(updateSpy).not.toHaveBeenCalled();
  });

  it('proceeds to save when mandatory and a due date is set', () => {
    const fixture = TestBed.createComponent(NewsEditDrawer);
    fixture.componentRef.setInput('newsId', null);
    const component = fixture.componentInstance as any;
    // The required fields, filled: the form is novalidate and says so itself now.
    component.title.set('სათაური');
    component.content.set('ტექსტი');

    const newsService = TestBed.inject(NewsService);
    const createSpy = vi.spyOn(newsService, 'createCommand').mockReturnValue(of({ id: 1, title: 'x' } as any));

    component.isMandatory.set(true);
    component.dueDate.set('2030-01-01');

    component.submit({ preventDefault: () => {} } as Event);

    expect(component.dueDateError()).toBe(false);
    expect(createSpy).toHaveBeenCalledTimes(1);
  });

  it('proceeds to save when not mandatory, regardless of due date', () => {
    const fixture = TestBed.createComponent(NewsEditDrawer);
    fixture.componentRef.setInput('newsId', null);
    const component = fixture.componentInstance as any;
    // The required fields, filled: the form is novalidate and says so itself now.
    component.title.set('სათაური');
    component.content.set('ტექსტი');

    const newsService = TestBed.inject(NewsService);
    const createSpy = vi.spyOn(newsService, 'createCommand').mockReturnValue(of({ id: 1, title: 'x' } as any));

    component.isMandatory.set(false);
    component.dueDate.set('');

    component.submit({ preventDefault: () => {} } as Event);

    expect(component.dueDateError()).toBe(false);
    expect(createSpy).toHaveBeenCalledTimes(1);
  });
});
