import { Component, computed, input, output } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { getCategoryCardStyles, getCategoryIcon, isRecentlyPublished } from '../category-visuals';
import { formatKaDate } from '../ka-date';
import { FavoriteStar } from '../favorite-star/favorite-star';

export interface ArticleCardViewModel {
  id: number;
  title: string;
  categoryName: string;
  createdAt: string;
  publishedAt: string | null;
  readTime: number;
}

/**
 * Single reusable card, replacing three template-divergent vanilla-JS
 * card implementations (KB default grid `.kb-card`, search-results card,
 * category-view `.kb-item-card` -- see Phase 3c slice-1 research notes in
 * the migration doc). Based primarily on the KB grid's richer template
 * (icon, category chip, date, read-time) since it's the most complete of
 * the three.
 */
@Component({
  selector: 'app-article-card',
  standalone: true,
  imports: [TranslatePipe, FavoriteStar],
  templateUrl: './article-card.html'
})
export class ArticleCard {
  readonly article = input.required<ArticleCardViewModel>();
  readonly opened = output<number>();

  protected readonly icon = computed(() => getCategoryIcon(this.article().categoryName, this.article().title));
  protected readonly styles = computed(() => getCategoryCardStyles(this.article().categoryName));
  protected readonly isNew = computed(() => isRecentlyPublished(this.article()));
  protected readonly dateLabel = computed(() => formatKaDate(this.article().createdAt));

  open(): void {
    this.opened.emit(this.article().id);
  }
}
