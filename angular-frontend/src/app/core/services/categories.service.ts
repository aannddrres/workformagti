import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, shareReplay } from 'rxjs';
import { Category } from '../models/category';

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
}
