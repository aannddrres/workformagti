import { Component, input } from '@angular/core';

/**
 * The company mark, in one place.
 *
 * Today it renders the same text wordmark the sidebar already had
 * (app-shell.html:14) — the repository contains no logo asset at all
 * (public/ holds only favicon.ico and i18n/), so a bold span was the whole
 * brand identity, and the login screen had not even that.
 *
 * TO SHIP THE REAL LOGO: drop the official SVG in public/ and replace the
 * <span> below with an <img> or an inline <svg>. Because every surface renders
 * this component, that is a one-file change — no hunting for places where the
 * word "MAGTI" was typed by hand.
 *
 * Deliberately not attempted here: inventing a mark. Redrawing a corporate
 * logo from memory produces something that is subtly wrong everywhere it
 * appears, which is worse than an honest placeholder.
 */
@Component({
  selector: 'app-logo',
  standalone: true,
  template: `
    <span
      class="font-bold tracking-wide text-brand"
      [class.text-lg]="size() === 'md'"
      [class.text-2xl]="size() === 'lg'"
      >MAGTI</span
    >
  `
})
export class Logo {
  /** md = sidebar/topbar, lg = login and other standalone surfaces. */
  readonly size = input<'md' | 'lg'>('md');
}
