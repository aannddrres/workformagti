import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { GlobalSearchResponse, SearchHit } from '../models/search';
import { isReaderVisibleArticle } from '../../shared/article-visibility';

/**
 * The one client for GET /api/search/global.
 *
 * This endpoint, its trigram index, its 60s TTL cache and its single-flight
 * guard were all built on the backend (SearchController:86-120,
 * SearchQueryService, TrigramIndexer) and had no caller in the Angular app at
 * all — no service, no component, no route. For a call-centre portal that is
 * the one thing an operator needs most while a customer waits, so the whole
 * subsystem was paid for and unreachable.
 *
 * Deliberately no client-side caching or debouncing here: the server already
 * caches identical queries for 60s, and debouncing is a UI-timing concern that
 * belongs with the component that owns the input.
 */
@Injectable({ providedIn: 'root' })
export class SearchService {
  private readonly http = inject(HttpClient);

  /** Below this the results are noise, and the backend does not even log the query. */
  static readonly MIN_QUERY_LENGTH = 2;

  searchGlobal(query: string): Observable<GlobalSearchResponse> {
    return this.http.get<GlobalSearchResponse>('/api/search/global', {
      params: new HttpParams().set('q', query),
    });
  }

  /**
   * Same request, flattened into one ordered list for the palette.
   *
   * Order is articles → news → videos, matching how the three groups are
   * rendered, so the keyboard index and the visual order cannot disagree.
   */
  searchHits(query: string): Observable<SearchHit[]> {
    return this.searchGlobal(query).pipe(map((response) => toHits(response, query)));
  }
}

export function toHits(response: GlobalSearchResponse, query = ''): SearchHit[] {
  const normalized = query.trim().toLocaleLowerCase('ka');
  return [
    ...response.articles.filter(isReaderVisibleArticle).map((a): SearchHit => ({
      itemType: 'article',
      id: a.id,
      title: a.title,
      context: a.category_name,
      categoryId: a.category_id,
      targetDepartments: a.target_departments,
      matchKind:
        normalized && a.title.toLocaleLowerCase('ka').includes(normalized) ? 'title' : 'other',
    })),
    ...response.news.map((n): SearchHit => ({
      itemType: 'news',
      id: n.id,
      title: n.title,
      context: n.target_department,
    })),
    ...response.videos.map((v): SearchHit => ({
      itemType: 'video',
      id: v.id,
      title: v.title,
      context: v.category,
    })),
  ];
}
