import { Component, input } from '@angular/core';

/** Official marks downloaded from magticom.ge and bundled for intranet use. */
@Component({
  selector: 'app-logo',
  standalone: true,
  template: `
    <img
      src="/magticom-logo.png"
      alt="მაგთიკომი"
      class="w-auto object-contain dark:hidden"
      [class.h-8]="size() === 'md'"
      [class.h-11]="size() === 'lg'"
    />
    <img
      src="/magticom-logo-white.png"
      alt="მაგთიკომი"
      class="hidden w-auto object-contain dark:block"
      [class.h-8]="size() === 'md'"
      [class.h-11]="size() === 'lg'"
    />
  `
})
export class Logo {
  /** md = sidebar/topbar, lg = login and other standalone surfaces. */
  readonly size = input<'md' | 'lg'>('md');
}
