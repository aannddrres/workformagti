import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, interval, switchMap, takeWhile } from 'rxjs';
import { ExportJobResponse, ExportStatus } from '../models/export';

@Injectable({ providedIn: 'root' })
export class ExportService {
  private readonly http = inject(HttpClient);

  exportReadingsCsv(): Observable<Blob> {
    return this.http.get('/api/export/readings', { responseType: 'blob' });
  }

  submitReadingsXlsx(): Observable<ExportJobResponse> {
    return this.http.get<ExportJobResponse>('/api/export/readings.xlsx');
  }

  submitReadingsPdf(): Observable<ExportJobResponse> {
    return this.http.get<ExportJobResponse>('/api/export/readings.pdf');
  }

  submitTeamStatsPdf(): Observable<ExportJobResponse> {
    return this.http.get<ExportJobResponse>('/api/export/team-stats.pdf');
  }

  status(jobId: string): Observable<ExportStatus> {
    return this.http.get<ExportStatus>(`/api/export/status/${jobId}`);
  }

  download(jobId: string): Observable<Blob> {
    return this.http.get(`/api/export/download/${jobId}`, { responseType: 'blob' });
  }

  /** Polls status every intervalMs until it leaves "processing"; emits every intermediate status too. */
  pollUntilDone(jobId: string, intervalMs = 1500): Observable<ExportStatus> {
    return interval(intervalMs).pipe(
      switchMap(() => this.status(jobId)),
      takeWhile((s) => s.status === 'processing', true)
    );
  }
}
