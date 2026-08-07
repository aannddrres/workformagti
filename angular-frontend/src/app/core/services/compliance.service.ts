import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, catchError, map, of } from 'rxjs';
import { MarkReadResult, MyProgress, MyReading, ReadStatus } from '../models/compliance';

@Injectable({ providedIn: 'root' })
export class ComplianceService {
  private readonly http = inject(HttpClient);

  myProgress(): Observable<MyProgress> {
    return this.http.get<MyProgress>('/api/compliance/my-progress');
  }

  myReadings(): Observable<MyReading[]> {
    return this.http.get<MyReading[]>('/api/compliance/my-readings');
  }

  /** 403 means the underlying article's quiz gate isn't satisfied -- reported as a distinct outcome, not an error. */
  markRead(readingId: number): Observable<MarkReadResult> {
    return this.http.post<ReadStatus>(`/api/compliance/mark-read/${readingId}`, {}).pipe(
      map((status) => ({ ok: true, quizRequired: false, status }) as const),
      catchError((err: HttpErrorResponse) => {
        if (err.status === 403) {
          return of({ ok: false, quizRequired: true } as const);
        }
        return of({ ok: false, quizRequired: false } as const);
      })
    );
  }
}
