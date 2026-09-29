import { Component, computed, inject, signal } from '@angular/core';
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
  private readonly categoriesService = inject(CategoriesService);
  private readonly categories = signal<Category[]>([]);

  protected readonly topLevel = computed(() =>
    this.categories().filter((category) => category.is_active && category.parent_id === null)
  );

  constructor() {
    this.categoriesService.list().subscribe({
      next: (categories) => this.categories.set(categories),
      error: () => this.categories.set([])
    });
  }

  protected icon(category: Category): string {
    return categoryIconClass(category, category.name);
  }

  protected route(category: Category): string[] {
    return ['/category', category.slug?.trim() || String(category.id)];
  }
}
