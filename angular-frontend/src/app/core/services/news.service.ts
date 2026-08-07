import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { NewsSummary } from '../models/news';

@Injectable({ providedIn: 'root' })
export class NewsService {
  private readonly http = inject(HttpClient);

  list(options: { skip?: number; limit?: number } = {}): Observable<NewsSummary[]> {
    const params = new HttpParams().set('skip', options.skip ?? 0).set('limit', options.limit ?? 20);
    return this.http.get<NewsSummary[]>('/api/news', { params });
  }
}
