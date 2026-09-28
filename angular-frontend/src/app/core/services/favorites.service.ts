import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { Favorite } from '../models/favorite';

function keyOf(itemType: string, itemId: number): string {
  return `${itemType}_${itemId}`;
}

/**
 * Port of Store.favorites / toggleFavorite (app-core.js:45-63, 1411-1444).
 * Same cache shape (`${type}_${id}` -> favorite row id, since that id is
 * what DELETE /api/favorites/{id} needs), loaded once for the session.
 *
 * Deliberate simplification: the original's toggleFavorite always follows
 * a POST/DELETE with a full `await fetchAndRenderFavorites(token)` --
 * re-fetching and rebuilding the entire favorites list just to learn the
 * one id it already has (POST's response) or already knew (the key it's
 * deleting). This service updates its own cache signal directly from the
 * mutation's own result instead of a redundant second round-trip.
 */
@Injectable({ providedIn: 'root' })
export class FavoritesService {
  private readonly http = inject(HttpClient);

  private readonly _favorites = signal<Favorite[]>([]);
  readonly favorites = this._favorites.asReadonly();

  private readonly favoriteIdByKey = computed(() => {
    const map = new Map<string, number>();
    for (const fav of this._favorites()) {
      map.set(keyOf(fav.item_type, fav.item_id), fav.id);
    }
    return map;
  });

  private readonly loaded = signal(false);

  /**
   * Writes that have completed, counted. The list is first read when the
   * first star renders -- exactly when a person is likely to click one -- so
   * a read can be answered after a click's write, describing the server as it
   * was before it. Applied, that unfilled a star the server had just stored
   * or brought back one just removed (20 of 100 repeated runs of the star's
   * E2E spec). A read overtaken by a write is dropped and taken again.
   */
  private writes = 0;

  constructor() {
    this.refresh().subscribe();
  }

  refresh(): Observable<Favorite[]> {
    const writesAtStart = this.writes;
    return this.http.get<Favorite[]>('/api/favorites').pipe(
      tap((favorites) => {
        if (this.writes !== writesAtStart) {
          this.refresh().subscribe();
          return;
        }
        this._favorites.set(favorites);
        this.loaded.set(true);
      })
    );
  }

  isReady(): boolean {
    return this.loaded();
  }

  isFavorited(itemType: string, itemId: number): boolean {
    return this.favoriteIdByKey().has(keyOf(itemType, itemId));
  }

  toggle(itemType: string, itemId: number): void {
    const key = keyOf(itemType, itemId);
    const existingId = this.favoriteIdByKey().get(key);
    if (existingId != null) {
      this.remove(existingId);
    } else {
      this.http.post<Favorite>('/api/favorites', { item_type: itemType, item_id: itemId }).subscribe({
        next: (favorite) => {
          this.writes++;
          this._favorites.set([...this._favorites().filter((f) => f.id !== favorite.id), favorite]);
        },
        error: () => void 0
      });
    }
  }

  remove(favoriteId: number): void {
    this.http.delete<void>(`/api/favorites/${favoriteId}`).subscribe({
      next: () => {
        this.writes++;
        this._favorites.set(this._favorites().filter((f) => f.id !== favoriteId));
      },
      error: () => void 0
    });
  }
}
