import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BroadcastRequest, BroadcastResult, MessageEntry, SendMessageRequest } from '../models/message';

@Injectable({ providedIn: 'root' })
export class MessagingService {
  private readonly http = inject(HttpClient);

  inbox(): Observable<MessageEntry[]> {
    return this.http.get<MessageEntry[]>('/api/messages');
  }

  sent(): Observable<MessageEntry[]> {
    return this.http.get<MessageEntry[]>('/api/messages/sent');
  }

  send(request: SendMessageRequest): Observable<MessageEntry> {
    return this.http.post<MessageEntry>('/api/messages', request);
  }

  markRead(messageId: number): Observable<MessageEntry> {
    return this.http.post<MessageEntry>(`/api/messages/${messageId}/read`, {});
  }

  remove(messageId: number): Observable<void> {
    return this.http.delete<void>(`/api/messages/${messageId}`);
  }

  broadcast(request: BroadcastRequest): Observable<BroadcastResult> {
    return this.http.post<BroadcastResult>('/api/broadcast', request);
  }
}
