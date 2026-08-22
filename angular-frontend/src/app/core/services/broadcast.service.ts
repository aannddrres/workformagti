import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  BroadcastAnnouncement,
  BroadcastHistory,
  PublishBroadcastRequest
} from '../models/broadcast';

@Injectable({ providedIn: 'root' })
export class BroadcastService {
  private readonly http = inject(HttpClient);

  active(): Observable<BroadcastAnnouncement[]> {
    return this.http.get<BroadcastAnnouncement[]>('/api/broadcasts');
  }

  history(page = 0, size = 50): Observable<BroadcastHistory> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<BroadcastHistory>('/api/broadcasts/history', { params });
  }

  publish(request: PublishBroadcastRequest): Observable<BroadcastAnnouncement> {
    return this.http.post<BroadcastAnnouncement>('/api/broadcasts', request);
  }

  endEarly(id: number): Observable<BroadcastAnnouncement> {
    return this.http.post<BroadcastAnnouncement>(`/api/broadcasts/${id}/end`, {});
  }
}
