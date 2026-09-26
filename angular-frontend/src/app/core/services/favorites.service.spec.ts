import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Favorite } from '../models/favorite';
import { FavoritesService } from './favorites.service';

const favorite = (id: number, itemId: number): Favorite =>
  ({ id, user_id: 1, item_type: 'article', item_id: itemId, item_title: `სტატია ${itemId}` }) as Favorite;

/**
 * The list is read once when the first star renders, which is also when a
 * person is most likely to click one. A read answered after a click's write
 * describes the server before that write. Applied, it unfilled a star the
 * server had just stored, or kept a removed one. In the repeated browser run
 * of e2e/shared-components.spec.ts that happened 20 times in 100.
 */
describe('FavoritesService', () => {
  let service: FavoritesService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(FavoritesService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('keeps a star added while the first read was still in flight', () => {
    const firstRead = http.expectOne('/api/favorites');

    service.toggle('article', 7);
    const add = http.expectOne((request) => request.method === 'POST' && request.url === '/api/favorites');
    add.flush(favorite(70, 7));
    expect(service.isFavorited('article', 7)).toBe(true);

    firstRead.flush([]);
    expect(service.isFavorited('article', 7)).toBe(true);

    // Read again, after the write.
    http.expectOne((request) => request.method === 'GET' && request.url === '/api/favorites')
      .flush([favorite(70, 7)]);
    expect(service.isFavorited('article', 7)).toBe(true);
    expect(service.isReady()).toBe(true);
  });

  it('keeps a star removed while a read was in flight', () => {
    http.expectOne('/api/favorites').flush([favorite(70, 7)]);
    expect(service.isFavorited('article', 7)).toBe(true);

    service.refresh().subscribe();
    const staleRead = http.expectOne('/api/favorites');

    service.toggle('article', 7);
    http.expectOne('/api/favorites/70').flush(null);
    expect(service.isFavorited('article', 7)).toBe(false);

    staleRead.flush([favorite(70, 7)]);
    expect(service.isFavorited('article', 7)).toBe(false);

    http.expectOne('/api/favorites').flush([]);
    expect(service.isFavorited('article', 7)).toBe(false);
  });

  it('applies a read that no write overtook', () => {
    http.expectOne('/api/favorites').flush([favorite(70, 7), favorite(80, 8)]);
    expect(service.isFavorited('article', 8)).toBe(true);
    expect(service.isReady()).toBe(true);
  });
});
