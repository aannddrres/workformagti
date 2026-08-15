import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, interval, switchMap, takeWhile, throwError, timer } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { ExportJobResponse, ExportStatus } from '../models/export';

/** Thrown by pollUntilDone when a job never leaves "processing". See POLL_TIMEOUT_MS. */
export class ExportPollTimeoutError extends Error {
  constructor() {
    super('export job never left "processing"');
    this.name = 'ExportPollTimeoutError';
  }
}

@Injectable({ providedIn: 'root' })
export class ExportService {
  private readonly http = inject(HttpClient);

  static readonly POLL_INTERVAL_MS = 1500;

  /**
   * Hard stop for the poll, derived from the backend rather than guessed:
   * ExportJobWorker.EXPORT_JOB_TTL_SECONDS is 3600, and ExportController's
   * download returns 410 once expires_at passes. So a job still "processing"
   * after the TTL can never become downloadable -- polling past this point
   * is guaranteed-useless traffic. Capped a little under the hour so the
   * client gives up before the row is swept rather than racing it.
   */
  static readonly POLL_TIMEOUT_MS = 55 * 60 * 1000;

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

  /**
   * Polls status every intervalMs until it leaves "processing"; emits every
   * intermediate status too.
   *
   * <p>Two things this must not do, both audit FE-03. It must not run
   * forever: a job stuck at "processing" used to poll at one request every
   * 1.5s -- ~2400 per hour, per open tab, indefinitely -- and the user saw a
   * spinner that never resolved either way. It now errors with
   * {@link ExportPollTimeoutError} at POLL_TIMEOUT_MS so the caller can show
   * something actionable.
   *
   * <p>And it must not outlive its subscriber: the caller is responsible for
   * takeUntilDestroyed, because a service cannot know the component's
   * lifetime. TeamStatsPage does exactly that.
   */
  pollUntilDone(jobId: string, intervalMs = ExportService.POLL_INTERVAL_MS): Observable<ExportStatus> {
    return interval(intervalMs).pipe(
      switchMap(() => this.status(jobId)),
      takeWhile((s) => s.status === 'processing', true),
      // Deliberately takeUntil(...) + throwError rather than RxJS timeout():
      // timeout() measures the gap BETWEEN emissions, so a job polling
      // healthily every 1.5s forever would never trigger it. This bounds the
      // total wall-clock life of the poll, which is the actual requirement.
      takeUntil(timer(ExportService.POLL_TIMEOUT_MS).pipe(switchMap(() => throwError(() => new ExportPollTimeoutError()))))
    );
  }
}
