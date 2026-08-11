import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  Article,
  ArticleBulkArchiveResponse,
  ArticleRequest,
  ArticleSummary,
  RecentlyViewedItem
} from '../models/article';
import { ArticleDiff, ArticleHistoryItem } from '../models/article-history';

@Injectable({ providedIn: 'root' })
export class ArticlesService {
  private readonly http = inject(HttpClient);

  list(
    options: { skip?: number; limit?: number; categoryId?: number; q?: string; status?: string } = {}
  ): Observable<ArticleSummary[]> {
    let params = new HttpParams()
      .set('skip', options.skip ?? 0)
      .set('limit', options.limit ?? 20);
    if (options.categoryId != null) {
      params = params.set('category_id', options.categoryId);
    }
    if (options.q) {
      params = params.set('q', options.q);
    }
    if (options.status) {
      params = params.set('status', options.status);
    }
    return this.http.get<ArticleSummary[]>('/api/articles', { params });
  }

  /** Admin content-management table: mirrors fetchAndRenderAdminContent's
   *  fetch-everything-then-paginate-client-side approach (no server-side
   *  pagination on this endpoint). */
  listAdmin(options: { q?: string; categoryId?: number; status?: string } = {}): Observable<ArticleSummary[]> {
    return this.list({ limit: 1000, ...options });
  }

  create(request: ArticleRequest): Observable<Article> {
    return this.http.post<Article>('/api/articles', request);
  }

  update(id: number, request: ArticleRequest): Observable<Article> {
    return this.http.put<Article>(`/api/articles/${id}`, request);
  }

  autosave(id: number, partial: Partial<ArticleRequest>): Observable<Article> {
    return this.http.patch<Article>(`/api/articles/${id}/autosave`, partial);
  }

  remove(id: number): Observable<void> {
    return this.http.delete<void>(`/api/articles/${id}`);
  }

  archive(id: number): Observable<Article> {
    return this.http.post<Article>(`/api/articles/${id}/archive`, {});
  }

  unarchive(id: number): Observable<Article> {
    return this.http.post<Article>(`/api/articles/${id}/unarchive`, {});
  }

  bulkArchive(ids: number[], archive: boolean): Observable<ArticleBulkArchiveResponse> {
    return this.http.post<ArticleBulkArchiveResponse>('/api/articles/bulk-archive', { ids, archive });
  }

  get(id: number): Observable<Article> {
    return this.http.get<Article>(`/api/articles/${id}`);
  }

  /** GET /api/search -- returns full Article (not ArticleSummary), and has
   *  no category_name field, unlike the list endpoint (see ArticleResponse
   *  vs ArticleSummaryResponse on the backend). Callers resolve the name
   *  themselves via CategoriesService. */
  search(q: string, categoryId?: number): Observable<Article[]> {
    let params = new HttpParams().set('q', q);
    if (categoryId != null) {
      params = params.set('category_id', categoryId);
    }
    return this.http.get<Article[]>('/api/search', { params });
  }

  /** Fire-and-forget view-tracking, matching openArticleModalById's
   *  "log the view, don't block on it" behavior. */
  logView(id: number): void {
    this.http.post<void>(`/api/articles/${id}/view`, {}).subscribe({ error: () => void 0 });
  }

  /** GET /api/me/recently-viewed -- server-backed, cross-device (distinct
   *  from the KB page's separate localStorage-only "recently viewed" strip). */
  recentlyViewed(): Observable<RecentlyViewedItem[]> {
    return this.http.get<RecentlyViewedItem[]>('/api/me/recently-viewed');
  }

  /** Admin-only raw revision list (routers/articles.py's get_article_history). */
  history(id: number): Observable<ArticleHistoryItem[]> {
    return this.http.get<ArticleHistoryItem[]>(`/api/articles/${id}/history`);
  }

  /** Quick-look diff of one historical snapshot against the CURRENT content
   *  (no compare_history_id/compare_to_predecessor -- mirrors quickLookDiff's
   *  always-vs-current behavior, not the richer predecessor-aware compare
   *  used by the reader-facing "ვერსიების ისტორია" overlay). */
  diff(id: number, historyId: number): Observable<ArticleDiff> {
    return this.http.get<ArticleDiff>(`/api/articles/${id}/history/${historyId}/diff`);
  }

  restoreVersion(id: number, historyId: number): Observable<Article> {
    return this.http.post<Article>(`/api/articles/${id}/history/${historyId}/restore`, {});
  }
}
