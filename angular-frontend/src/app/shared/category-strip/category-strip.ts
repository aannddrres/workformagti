import { Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Category } from '../../core/models/category';
import { CategoriesService } from '../../core/services/categories.service';
import { categoryIconClass } from '../category-visuals';

/**
 * One row of the knowledge-base categories, for the work pages that took the
 * place of "მთავარი" for managers and content administrators (owner decision
 * კ12): a manager answering a question from the team, or an editor checking
 * what operators see, is one click from the category rather than three. The
 * system overview keeps its own, larger grid.
 *
 * The knowledge base and a category page pass their own list and article
 * counts (the top level, or a category's children); categories with nothing
 * to read are left out, as the tile grid it replaced did (owner decision
 * კ10). Without them it shows the top level.
 *
 * A failed load renders nothing. The strip is a shortcut, not the page's
 * content, and an error box above the team's numbers would outrank them.
 */
@Component({
  selector: 'app-category-strip',
  standalone: true,
  imports: [RouterLink, TranslatePipe],
  templateUrl: './category-strip.html'
})
export class CategoryStrip {
  readonly categories = input<Category[] | null>(null);
  readonly counts = input<Map<number, number> | null>(null);

  private readonly categoriesService = inject(CategoriesService);
  private readonly loaded = signal<Category[]>([]);

  protected readonly shown = computed(() => {
    const counts = this.counts();
    const list =
      this.categories() ?? this.loaded().filter((category) => category.is_active && category.parent_id === null);
    return counts ? list.filter((category) => (counts.get(category.id) ?? 0) > 0) : list;
  });

  constructor() {
    this.categoriesService.list().subscribe({
      next: (categories) => this.loaded.set(categories),
      error: () => this.loaded.set([])
    });
  }

  protected icon(category: Category): string {
    return categoryIconClass(category);
  }

  protected route(category: Category): string[] {
    return ['/category', category.slug?.trim() || String(category.id)];
  }
}
