import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Article, ArticleSummary } from '../models/article';

@Injectable({ providedIn: 'root' })
export class ArticlesService {
  private readonly http = inject(HttpClient);

  list(options: { skip?: number; limit?: number; categoryId?: number } = {}): Observable<ArticleSummary[]> {
    let params = new HttpParams()
      .set('skip', options.skip ?? 0)
      .set('limit', options.limit ?? 20);
    if (options.categoryId != null) {
      params = params.set('category_id', options.categoryId);
    }
    return this.http.get<ArticleSummary[]>('/api/articles', { params });
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
}
