import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { News, NewsCommandRequest, NewsRequest, NewsSummary } from '../models/news';

@Injectable({ providedIn: 'root' })
export class NewsService {
  private readonly http = inject(HttpClient);

  list(options: { skip?: number; limit?: number } = {}): Observable<NewsSummary[]> {
    const params = new HttpParams().set('skip', options.skip ?? 0).set('limit', options.limit ?? 20);
    return this.http.get<NewsSummary[]>('/api/news', { params });
  }

  /** Admin content-management table: mirrors fetchAndRenderAdminNews's
   *  fetch-everything (no client pagination on this table in Python either). */
  listAdmin(): Observable<NewsSummary[]> {
    return this.list({ limit: 1000 });
  }

  get(id: number): Observable<News> {
    return this.http.get<News>(`/api/news/${id}`);
  }

  create(request: NewsRequest): Observable<News> {
    return this.http.post<News>('/api/news', request);
  }

  update(id: number, request: NewsRequest): Observable<News> {
    return this.http.put<News>(`/api/news/${id}`, request);
  }

  createCommand(request: NewsCommandRequest): Observable<News> {
    return this.http.post<News>('/api/news/command', request);
  }

  updateCommand(id: number, request: NewsCommandRequest): Observable<News> {
    return this.http.put<News>(`/api/news/${id}/command`, request);
  }

  remove(id: number): Observable<void> {
    return this.http.delete<void>(`/api/news/${id}`);
  }

  archive(id: number): Observable<News> {
    return this.http.post<News>(`/api/news/${id}/archive`, {});
  }

  unarchive(id: number): Observable<News> {
    return this.http.post<News>(`/api/news/${id}/unarchive`, {});
  }
}
