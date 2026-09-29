import { vi } from 'vitest';
import { of } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';
import { ArticleEditDrawer } from './article-edit-drawer';
import { ArticlesService } from '../../../core/services/articles.service';
import { ConfirmService } from '../../../core/notifications/confirm.service';
import { RequiredReadingService } from '../../../core/services/required-reading.service';

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

  // submit() is async since PO-40: it may ask before a save that pauses an obligation.
  it('proceeds to save when mandatory and a due date is set', async () => {
    const fixture = TestBed.createComponent(ArticleEditDrawer);
    fixture.componentRef.setInput('articleId', null);
    const component = fixture.componentInstance as any;

    const articlesService = TestBed.inject(ArticlesService);
    const createSpy = vi
      .spyOn(articlesService, 'createCommand')
      .mockReturnValue(of({ id: 1, title: 'x' } as any));
    component.richTextEditor = () => ({ getHtml: () => '<p>x</p>' });

    // A new article starts as a draft (კ20), and a draft cannot be mandatory (PO-40).
    component.status.set('published');
    component.deptChecked.set({ info: true, tech: false, office: false });
    component.isMandatory.set(true);
    component.dueDate.set('2030-01-01');

    await component.submit({ preventDefault: () => {} } as Event);

    expect(component.dueDateError()).toBe(false);
    expect(createSpy).toHaveBeenCalledTimes(1);
  });
});

/**
 * PO-40: nobody is bound to what they cannot open. A draft cannot be made
 * mandatory, a scheduled one binds from publication, and a change that takes
 * a mandatory article out of someone's reach asks first, naming them.
 */
describe('ArticleEditDrawer mandatory reach', () => {
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

  function drawer() {
    const fixture = TestBed.createComponent(ArticleEditDrawer);
    fixture.componentRef.setInput('articleId', null);
    const component = fixture.componentInstance as any;
    component.richTextEditor = () => ({ getHtml: () => '<p>x</p>' });
    return component;
  }

  it('does not offer mandatory on a draft that is not already mandatory', () => {
    const component = drawer();
    component.status.set('draft');
    expect(component.mandatoryAllowed()).toBe(false);

    component.wasMandatory.set(true);
    expect(component.mandatoryAllowed()).toBe(true);
  });

  it('refuses a deadline before a scheduled publication', async () => {
    const component = drawer();
    const createSpy = vi.spyOn(TestBed.inject(ArticlesService), 'createCommand');
    component.deptChecked.set({ info: true, tech: false, office: false });
    component.status.set('scheduled');
    component.scheduledAt.set('2031-05-10T09:00');
    component.isMandatory.set(true);
    component.dueDate.set('2031-05-01');

    await component.submit({ preventDefault: () => {} } as Event);

    expect(component.dueBeforePublication()).toBe(true);
    expect(createSpy).not.toHaveBeenCalled();
  });

  it('asks before unpublishing a mandatory article and stops when the editor declines', async () => {
    const component = drawer();
    const ask = vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockResolvedValue(false);
    const createSpy = vi.spyOn(TestBed.inject(ArticlesService), 'createCommand');
    component.deptChecked.set({ info: true, tech: false, office: false });
    component.wasMandatory.set(true);
    component.mandatoryAudience.set({
      in_force_total: 2,
      pending_total: 0,
      departments: [{ department: 'საინფორმაციო', in_force: 2, pending: 0, read: 1 }],
      addressees: [{ user_id: 7, user_name: 'ნინო', department: 'საინფორმაციო', read: true, pending: false }]
    });
    component.isMandatory.set(true);
    component.dueDate.set('2031-01-01');
    component.status.set('draft');

    await component.submit({ preventDefault: () => {} } as Event);

    expect(ask).toHaveBeenCalledTimes(1);
    expect(ask.mock.calls[0][0]).toMatchObject({ details: ['ნინო', 'content.articles.mandatory_loss_more'] });
    expect(createSpy).not.toHaveBeenCalled();
  });

  it('saves without asking when the change keeps everyone in reach', async () => {
    const component = drawer();
    const ask = vi.spyOn(TestBed.inject(ConfirmService), 'ask');
    const createSpy = vi.spyOn(TestBed.inject(ArticlesService), 'createCommand')
      .mockReturnValue(of({ id: 1, title: 'x' } as any));
    component.deptChecked.set({ info: true, tech: false, office: false });
    component.wasMandatory.set(true);
    component.mandatoryAudience.set({
      in_force_total: 2,
      pending_total: 0,
      departments: [{ department: 'საინფორმაციო', in_force: 2, pending: 0, read: 0 }],
      addressees: []
    });
    component.status.set('published');
    component.isMandatory.set(true);
    component.dueDate.set('2031-01-01');

    await component.submit({ preventDefault: () => {} } as Event);

    expect(ask).not.toHaveBeenCalled();
    expect(createSpy).toHaveBeenCalledTimes(1);
  });
});

/**
 * კ20: a new article starts as a draft, so publishing is a choice the editor
 * makes. An article opened for editing keeps the status it has. Only status
 * changes here; is_draft is the personal-autosave flag and stays false.
 */
describe('ArticleEditDrawer starting status', () => {
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

  function drawer(articleId: number | null) {
    const fixture = TestBed.createComponent(ArticleEditDrawer);
    fixture.componentRef.setInput('articleId', articleId);
    const component = fixture.componentInstance as any;
    component.richTextEditor = () => ({ getHtml: () => '<p>x</p>', setHtml: () => {}, clear: () => {} });
    return component;
  }

  it('starts a new article as a draft, and saves it as one', async () => {
    const component = drawer(null);
    expect(component.status()).toBe('draft');

    component.status.set('published');
    component.resetForCreate();
    expect(component.status()).toBe('draft');

    const createSpy = vi.spyOn(TestBed.inject(ArticlesService), 'createCommand')
      .mockReturnValue(of({ id: 1, title: 'x' } as any));
    component.deptChecked.set({ info: true, tech: false, office: false });
    await component.submit({ preventDefault: () => {} } as Event);

    expect(createSpy.mock.calls[0][0].article).toMatchObject({ status: 'draft', is_draft: false });
  });

  it('keeps the status of an article opened for editing', () => {
    vi.spyOn(TestBed.inject(RequiredReadingService), 'byItem').mockReturnValue(of(null));
    vi.spyOn(TestBed.inject(ArticlesService), 'get').mockReturnValue(of({
      id: 5, title: 'x', content: '<p>x</p>', category_id: 1, tags: null,
      target_departments: ['საინფორმაციო'], status: 'published', published_at: null,
      attachment_url: null, audience_profile: 'all', visible_to_tech_info: true,
      visible_to_service_center: false, quiz_enabled: false
    } as any));
    const component = drawer(5);

    component.loadForEdit(5);

    expect(component.status()).toBe('published');
  });
});
