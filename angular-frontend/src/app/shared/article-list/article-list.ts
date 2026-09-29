import { Component, input, output } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { FavoriteStar } from '../favorite-star/favorite-star';
import { isRecentlyPublished } from '../category-visuals';
import { formatKaDate } from '../ka-date';

export interface ArticleListItem {
  id: number;
  title: string;
  categoryName: string;
  /** The category's own icon (`categoryIconClass`), never one guessed from the title. */
  categoryIcon: string;
  /** "Parent › Child" in search results, where rows come from many categories. */
  categoryContext?: string;
  createdAt: string;
  publishedAt: string | null;
  excerpt?: string;
  matchKind?: 'title' | 'other';
}

/**
 * The knowledge base as a list, one article per line (owner decision კ10).
 *
 * It was a grid of 160px cards, four across: at 1080p an operator saw about
 * eight titles before scrolling, each clamped to two lines under a coloured
 * icon guessed from keywords in the title. A row shows the title, the
 * category, the date and the star, so fifteen or more fit on one screen and
 * the eye runs down a single column of titles.
 *
 * The row is not a link: the title is the button (what Tab reaches and what
 * a screen reader announces), and a click anywhere else on the row does the
 * same for the mouse. The star stops its own click from reaching the row.
 */
@Component({
  selector: 'app-article-list',
  standalone: true,
  imports: [TranslatePipe, FavoriteStar],
  templateUrl: './article-list.html'
})
export class ArticleList {
  readonly items = input.required<ArticleListItem[]>();
  readonly opened = output<number>();

  protected open(item: ArticleListItem): void {
    this.opened.emit(item.id);
  }

  protected isNew(item: ArticleListItem): boolean {
    return isRecentlyPublished(item);
  }

  protected date(item: ArticleListItem): string {
    return formatKaDate(item.publishedAt || item.createdAt);
  }
}
