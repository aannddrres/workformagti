import { vi } from 'vitest';
import { of } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';
import { VideoEditDrawer } from './video-edit-drawer';
import { VideosService } from '../../../core/services/videos.service';

/** Covers submit()'s due-date guard (video-edit-drawer.ts:133-139). */
describe('VideoEditDrawer due-date validation', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [VideoEditDrawer],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideTranslateService({ lang: 'ka', fallbackLang: 'ka' }),
        provideTranslateHttpLoader({ prefix: '/i18n/', suffix: '.json' })
      ]
    }).compileComponents();
  });

  it('blocks submit and sets dueDateError when mandatory but due date is empty', () => {
    const fixture = TestBed.createComponent(VideoEditDrawer);
    fixture.componentRef.setInput('videoId', null);
    const component = fixture.componentInstance as any;

    const videosService = TestBed.inject(VideosService);
    const createSpy = vi.spyOn(videosService, 'createCommand');
    const updateSpy = vi.spyOn(videosService, 'updateCommand');

    component.isMandatory.set(true);
    component.dueDate.set('');

    component.submit({ preventDefault: () => {} } as Event);

    expect(component.dueDateError()).toBe(true);
    expect(createSpy).not.toHaveBeenCalled();
    expect(updateSpy).not.toHaveBeenCalled();
  });

  it('proceeds to save when mandatory and a due date is set', () => {
    const fixture = TestBed.createComponent(VideoEditDrawer);
    fixture.componentRef.setInput('videoId', null);
    const component = fixture.componentInstance as any;

    const videosService = TestBed.inject(VideosService);
    const createSpy = vi.spyOn(videosService, 'createCommand').mockReturnValue(of({ id: 1, title: 'x' } as any));

    component.isMandatory.set(true);
    component.dueDate.set('2030-01-01');

    component.submit({ preventDefault: () => {} } as Event);

    expect(component.dueDateError()).toBe(false);
    expect(createSpy).toHaveBeenCalledTimes(1);
  });

  it('proceeds to save when not mandatory, regardless of due date', () => {
    const fixture = TestBed.createComponent(VideoEditDrawer);
    fixture.componentRef.setInput('videoId', null);
    const component = fixture.componentInstance as any;

    const videosService = TestBed.inject(VideosService);
    const createSpy = vi.spyOn(videosService, 'createCommand').mockReturnValue(of({ id: 1, title: 'x' } as any));

    component.isMandatory.set(false);
    component.dueDate.set('');

    component.submit({ preventDefault: () => {} } as Event);

    expect(component.dueDateError()).toBe(false);
    expect(createSpy).toHaveBeenCalledTimes(1);
  });
});
