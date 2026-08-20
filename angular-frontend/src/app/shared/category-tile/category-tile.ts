import { Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Category } from '../../core/models/category';
import { getCategoryIcon } from '../category-visuals';

/**
 * Reused for both the dashboard's category grid and the KB page's bento
 * grid, which had two divergent implementations in the original
 * (renderDashboardCategoryGrid used `category.icon || getCategoryIcon(...)`;
 * the KB bento used a separate hardcoded-name lookup table that silently
 * fell back to a generic folder icon for any category not in its list).
 * Consolidated on the more general icon fallback.
 */
@Component({
  selector: 'app-category-tile',
  standalone: true,
  imports: [RouterLink, TranslatePipe],
  templateUrl: './category-tile.html'
})
export class CategoryTile {
  readonly category = input.required<Category>();
  readonly articleCount = input(0);
  readonly hasRecent = input(false);

  protected readonly icon = computed(() => this.category().icon || getCategoryIcon(this.category().name, ''));
  /** Historical rows may predate mandatory slugs; the category page already accepts an ID route. */
  protected readonly routeKey = computed(() => this.category().slug?.trim() || String(this.category().id));
}
