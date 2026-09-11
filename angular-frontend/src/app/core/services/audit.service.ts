import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { AuditChainHealth, AuditLogFilter, AuditLogPage, AuditVerifyResult } from '../models/audit';

function buildParams(filter: AuditLogFilter, limit: number, offset: number): HttpParams {
  let params = new HttpParams().set('limit', limit).set('offset', offset);
  const entries: [string, string | number | null | undefined][] = [
    ['start_date', filter.start_date],
    ['end_date', filter.end_date],
    ['user_id', filter.user_id],
    ['user_name', filter.user_name],
    ['action', filter.action],
    ['category', filter.category],
    ['q', filter.q]
  ];
  for (const [key, value] of entries) {
    if (value !== null && value !== undefined && value !== '') {
      params = params.set(key, value);
    }
  }
  return params;
}

@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly http = inject(HttpClient);

  /**
   * @param direction only the chronological order is sortable. The trail is
   *   tamper-evident and its meaningful order is time; "find these rows" is
   *   already answered by the actor, category and date filters.
   */
  list(filter: AuditLogFilter, limit: number, offset: number, direction: 'asc' | 'desc' = 'desc'): Observable<AuditLogPage> {
    const params = buildParams(filter, limit, offset).set('direction', direction);
    return this.http
      .get('/api/audit-logs', { params, observe: 'response' })
      .pipe(
        map((res) => ({
          rows: (res.body as AuditLogPage['rows']) ?? [],
          total: Number(res.headers.get('X-Total-Count') ?? '0')
        }))
      );
  }

  verify(id: number): Observable<AuditVerifyResult> {
    return this.http.get<AuditVerifyResult>(`/api/audit-logs/${id}/verify`);
  }

  chainHealth(n = 100): Observable<AuditChainHealth> {
    return this.http.get<AuditChainHealth>('/api/audit-logs/chain-health', { params: { n } });
  }

  exportCsv(filter: AuditLogFilter): Observable<Blob> {
    const params = buildParams(filter, 0, 0).delete('limit').delete('offset');
    return this.http.get('/api/audit-logs/export', { params, responseType: 'blob' });
  }
}
