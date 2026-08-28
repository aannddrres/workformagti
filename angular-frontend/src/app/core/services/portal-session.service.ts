import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface PortalSessionEntry {
  id: string;
  created_at: string;
  last_seen_at: string;
  expires_at: string;
  revoked_at: string | null;
  client_ip: string | null;
  user_agent: string | null;
  current: boolean;
}

@Injectable({ providedIn: 'root' })
export class PortalSessionApi {
  private readonly http = inject(HttpClient);
  list(): Observable<PortalSessionEntry[]> { return this.http.get<PortalSessionEntry[]>('/api/auth/sessions'); }
  revoke(id: string): Observable<void> { return this.http.delete<void>(`/api/auth/sessions/${encodeURIComponent(id)}`); }
}
