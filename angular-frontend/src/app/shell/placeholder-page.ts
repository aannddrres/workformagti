import { Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { TranslatePipe } from '@ngx-translate/core';

/**
 * Stand-in for every real page until Phase 3c ports it. Shows the route's
 * `data.title` (an i18n key) so the shell/routing skeleton is navigable and
 * demonstrable end to end without pulling actual page content in early.
 */
@Component({
  selector: 'app-placeholder-page',
  standalone: true,
  imports: [TranslatePipe],
  template: `
    <div class="p-6">
      <h1 class="mb-2 text-2xl font-semibold text-gray-800 dark:text-gray-100">{{ titleKey() | translate }}</h1>
      <p class="text-sm text-gray-500 dark:text-gray-400">{{ 'shell.placeholder.message' | translate }}</p>
    </div>
  `
})
export class PlaceholderPage {
  private readonly route = inject(ActivatedRoute);
  protected readonly titleKey = toSignal(
    this.route.data.pipe(map((data) => (data['title'] as string) ?? '')),
    { initialValue: '' }
  );
}
