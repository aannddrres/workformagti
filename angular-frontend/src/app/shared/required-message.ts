import { Directive, ElementRef, inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

type RequiredControl = HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;

/**
 * The browser's own "this field is required" bubble, in Georgian.
 *
 * Chrome writes that bubble in its own interface language, not the page's:
 * "Please fill out this field." and "Please select an item in the list." --
 * measured on 2026-10-01 with the page's locale set to ka-GE (audit ⑦). Five
 * admin forms rely on it. The browser keeps doing the blocking; this only
 * replaces the words, through setCustomValidity, as the invalid event fires.
 */
@Directive({
  // Every required control, by what makes it required rather than by an
  // attribute someone has to remember -- a new field gets Georgian for free.
  // eslint-disable-next-line @angular-eslint/directive-selector
  selector: 'input[required],select[required],textarea[required]',
  standalone: true,
  host: {
    '(invalid)': 'explain()',
    '(input)': 'clear()',
    '(change)': 'clear()'
  }
})
export class RequiredMessage {
  private readonly host = inject<ElementRef<RequiredControl>>(ElementRef);
  private readonly translate = inject(TranslateService);

  protected explain(): void {
    const control = this.host.nativeElement;
    if (!control.validity.valueMissing) {
      return;
    }
    control.setCustomValidity(control instanceof HTMLSelectElement
      ? this.translate.instant('shared.validation.select_required')
      : this.translate.instant('shared.validation.required'));
  }

  /** A custom message makes the control invalid on its own, so it must go the moment the value changes. */
  protected clear(): void {
    this.host.nativeElement.setCustomValidity('');
  }
}
