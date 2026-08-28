import { vi } from 'vitest';
import { of } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';
import { ArticleEditDrawer } from './article-edit-drawer';
import { ArticlesService } from '../../../core/services/articles.service';

/**
 * Covers submit()'s guard order (article-edit-drawer.ts:251-265): department
 * selection is checked first, then the mandatory/due-date guard. A mandatory
 * article with no due date must flag dueDateError and never reach
 * ArticlesService.create/update, provided at least one department is picked
 * (otherwise departmentError fires first and dueDateError is never touched).
 *
 * The positive path monkeypatches richTextEditor() -- a required viewChild
 * that's only populated once the template renders -- since this test never
 * calls detectChanges() (Quill/QuizBuilder aren't under test here).
 */
describe('ArticleEditDrawer due-date validation', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ArticleEditDrawer],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideTranslateService({ lang: 'ka', fallbackLang: 'ka' }),
        provideTranslateHttpLoader({ prefix: '/i18n/', suffix: '.json' })
      ]
    }).compileComponents();
  });

  it('blocks submit and sets dueDateError when mandatory but due date is empty', () => {
    const fixture = TestBed.createComponent(ArticleEditDrawer);
    fixture.componentRef.setInput('articleId', null);
    const component = fixture.componentInstance as any;

    const articlesService = TestBed.inject(ArticlesService);
    const createSpy = vi.spyOn(articlesService, 'createCommand');
    const updateSpy = vi.spyOn(articlesService, 'updateCommand');

    component.deptChecked.set({ info: true, tech: false, office: false });
    component.isMandatory.set(true);
    component.dueDate.set('');

    component.submit({ preventDefault: () => {} } as Event);

    expect(component.dueDateError()).toBe(true);
    expect(createSpy).not.toHaveBeenCalled();
    expect(updateSpy).not.toHaveBeenCalled();
  });

  it('proceeds to save when mandatory and a due date is set', () => {
    const fixture = TestBed.createComponent(ArticleEditDrawer);
    fixture.componentRef.setInput('articleId', null);
    const component = fixture.componentInstance as any;

    const articlesService = TestBed.inject(ArticlesService);
    const createSpy = vi
      .spyOn(articlesService, 'createCommand')
      .mockReturnValue(of({ id: 1, title: 'x' } as any));
    component.richTextEditor = () => ({ getHtml: () => '<p>x</p>' });

    component.deptChecked.set({ info: true, tech: false, office: false });
    component.isMandatory.set(true);
    component.dueDate.set('2030-01-01');

    component.submit({ preventDefault: () => {} } as Event);

    expect(component.dueDateError()).toBe(false);
    expect(createSpy).toHaveBeenCalledTimes(1);
  });
});
