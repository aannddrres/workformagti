import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ReminderEntry, ReminderPage } from '../models/reminder';

@Injectable({ providedIn: 'root' })
export class ReminderService {
  private readonly http = inject(HttpClient);

  inbox(page = 0, size = 20): Observable<ReminderPage> {
    return this.http.get<ReminderPage>('/api/reminders', { params: { page, size } });
  }

  markRead(reminderId: number): Observable<ReminderEntry> {
    return this.http.post<ReminderEntry>(`/api/reminders/${reminderId}/read`, {});
  }

  sendManual(userId: number): Observable<ReminderEntry> {
    return this.http.post<ReminderEntry>(`/api/reminders/users/${userId}/send`, {});
  }
}
