import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { EMPTY, Observable, expand, reduce } from 'rxjs';
import { News, NewsCommandRequest, NewsRequest, NewsSummary } from '../models/news';

/** ListQueryBounds.MAX_LIMIT on the server. */
const LIST_PAGE_SIZE = 1000;

@Injectable({ providedIn: 'root' })
export class NewsService {
  private readonly http = inject(HttpClient);

  list(options: { skip?: number; limit?: number } = {}): Observable<NewsSummary[]> {
    const params = new HttpParams().set('skip', options.skip ?? 0).set('limit', options.limit ?? 20);
    return this.http.get<NewsSummary[]>('/api/news', { params });
  }

  /** Admin content-management table: every item, a page at a time (see ArticlesService.listAll). */
  listAdmin(): Observable<NewsSummary[]> {
    const page = (skip: number) => this.list({ skip, limit: LIST_PAGE_SIZE });
    return page(0).pipe(
      expand((rows, index) => (rows.length === LIST_PAGE_SIZE ? page((index + 1) * LIST_PAGE_SIZE) : EMPTY)),
      reduce((all, rows) => all.concat(rows), [] as NewsSummary[]),
    );
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
