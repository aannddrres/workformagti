import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, shareReplay } from 'rxjs';
import { Category, CategoryRequest } from '../models/category';

@Injectable({ providedIn: 'root' })
export class CategoriesService {
  private readonly http = inject(HttpClient);

  /** Categories change rarely; one shared, cached request per app session
   *  (mirrors the old frontend's Store.markReady('categories') pattern,
   *  without the manual global-state bookkeeping). */
  private readonly all$ = this.http
    .get<Category[]>('/api/categories')
    .pipe(shareReplay({ bufferSize: 1, refCount: false }));

  list(): Observable<Category[]> {
    return this.all$;
  }

  /** Uncached fetch for the admin screen, which must see its own writes
   *  immediately rather than the shared session-lifetime `list()` cache. */
  listAdmin(): Observable<Category[]> {
    return this.http.get<Category[]>('/api/categories');
  }

  create(payload: CategoryRequest): Observable<Category> {
    return this.http.post<Category>('/api/categories', payload);
  }

  update(id: number, payload: CategoryRequest): Observable<Category> {
    return this.http.put<Category>(`/api/categories/${id}`, payload);
  }

  remove(id: number): Observable<void> {
    return this.http.delete<void>(`/api/categories/${id}`);
  }
}
