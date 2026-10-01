import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslateService, provideTranslateService } from '@ngx-translate/core';
import { RequiredMessage } from './required-message';

@Component({
  standalone: true,
  imports: [RequiredMessage],
  template: `<form><input id="title" required><select id="category" required></select></form>`
})
class RequiredFormHost {}

describe('RequiredMessage', () => {
  function setUp(): HTMLElement {
    TestBed.configureTestingModule({ providers: [provideTranslateService({ lang: 'ka', fallbackLang: 'ka' })] });
    TestBed.inject(TranslateService).setTranslation('ka', {
      shared: { validation: { required: 'შეავსეთ ეს ველი.', select_required: 'აირჩიეთ სიიდან.' } }
    });
    const fixture = TestBed.createComponent(RequiredFormHost);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('replaces the browser wording with Georgian when an empty required control is reported', () => {
    const root = setUp();
    const title = root.querySelector<HTMLInputElement>('#title')!;
    const category = root.querySelector<HTMLSelectElement>('#category')!;

    expect(title.checkValidity()).toBe(false);
    expect(category.checkValidity()).toBe(false);

    expect(title.validationMessage).toBe('შეავსეთ ეს ველი.');
    expect(category.validationMessage).toBe('აირჩიეთ სიიდან.');
  });

  it('lets the control become valid again once something is entered', () => {
    const root = setUp();
    const title = root.querySelector<HTMLInputElement>('#title')!;
    title.checkValidity();

    title.value = 'სათაური';
    title.dispatchEvent(new Event('input'));

    expect(title.checkValidity()).toBe(true);
  });
});
