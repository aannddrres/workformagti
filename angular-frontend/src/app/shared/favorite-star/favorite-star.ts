import { Component, inject, input } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { FavoritesService } from '../../core/services/favorites.service';

/**
 * Single reusable star toggle, replacing 5 template-divergent vanilla-JS
 * implementations (KB grid, KB search results, news row, video card each
 * had their own class set -- category-view had no star at all, see the
 * Favorites slice research in the migration doc). `variant` picks between
 * the light-card default and the `dark` treatment needed over a video
 * thumbnail (the original's video star used text-white/80 for exactly
 * this reason).
 */
@Component({
  selector: 'app-favorite-star',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './favorite-star.html'
})
export class FavoriteStar {
  private readonly favoritesService = inject(FavoritesService);

  readonly itemType = input.required<string>();
  readonly itemId = input.required<number>();
  readonly variant = input<'light' | 'dark'>('light');

  get isFavorited(): boolean {
    return this.favoritesService.isFavorited(this.itemType(), this.itemId());
  }

  toggle(event: Event): void {
    // Both are needed. stopPropagation keeps the click from reaching a row's
    // own click handler (news rows, video cards). preventDefault is for a row
    // that is a link -- the reading list's -- where opening the item is the
    // link's default action, not a handler, and stopPropagation alone let
    // starring a reading open it as well.
    event.preventDefault();
    event.stopPropagation();
    this.favoritesService.toggle(this.itemType(), this.itemId());
  }
}
