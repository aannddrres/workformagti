import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { FavoritesService } from '../../core/services/favorites.service';
import { Favorite } from '../../core/models/favorite';
import { detailRouteFor, iconForContentType } from '../../shared/content-type-visuals';

const GROUP_ORDER = ['article', 'news', 'video'] as const;

/**
 * Port of page-favorites (base-layout.html:1238-1247) + the profile
 * cabinet's "ჩემი რჩეულები" tab mirror (base-layout.html:1259-1260,1322)
 * -- the original copy-pastes fetchAndRenderFavorites' output into two
 * separate DOM containers; this is one component used by both /favorites
 * and /profile/favorites routes instead. `item_type === 'category'` is
 * deliberately not grouped here -- confirmed dead in the original
 * (nothing ever calls toggleFavorite('category', ...), so no favorite
 * row of that type can exist).
 */
@Component({
  selector: 'app-favorites-page',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './favorites-page.html'
})
export class FavoritesPage {
  protected readonly favoritesService = inject(FavoritesService);
  private readonly translate = inject(TranslateService);
  private readonly router = inject(Router);

  protected readonly loading = signal(true);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly groups = computed(() => {
    const byType = new Map<string, Favorite[]>();
    for (const fav of this.favoritesService.favorites()) {
      const list = byType.get(fav.item_type) ?? [];
      list.push(fav);
      byType.set(fav.item_type, list);
    }
    return GROUP_ORDER.map((type) => ({ type, items: byType.get(type) ?? [] })).filter((g) => g.items.length > 0);
  });

  constructor() {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.errorMessage.set(null);
    this.favoritesService.refresh().subscribe({
      next: () => this.loading.set(false),
      error: () => {
        this.errorMessage.set(this.translate.instant('favorites.page.load_error'));
        this.loading.set(false);
      }
    });
  }

  iconFor(itemType: string): string {
    return iconForContentType(itemType);
  }

  groupLabel(itemType: string): string {
    return this.translate.instant(`favorites.page.group_${itemType}`);
  }

  open(favorite: Favorite): void {
    const route = detailRouteFor(favorite.item_type, favorite.item_id);
    if (route) {
      this.router.navigate(route);
    }
  }

  remove(favorite: Favorite, event: Event): void {
    event.stopPropagation();
    this.favoritesService.remove(favorite.id);
  }
}
