import { vi } from 'vitest';
import { of } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ExportPollTimeoutError, ExportService } from './export.service';
import { ExportStatus } from '../models/export';

/**
 * Audit FE-03. The bug was not that polling was wrong, but that it had no
 * end: `interval(1500) -> switchMap(status) -> takeWhile(processing)` with
 * no teardown and no cap. A job stuck at "processing" polled at ~2400
 * requests an hour, per open tab, forever, and the user watched a spinner
 * that could never resolve either way.
 */
describe('ExportService.pollUntilDone', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function serviceAlwaysReturning(status: ExportStatus['status']): ExportService {
    const service = TestBed.inject(ExportService);
    vi.spyOn(service, 'status').mockReturnValue(of({ job_id: 'j1', status }));
    return service;
  }

  it('gives up with ExportPollTimeoutError instead of polling a stuck job forever', () => {
    const service = serviceAlwaysReturning('processing');
    let error: unknown = null;
    let completed = false;

    service.pollUntilDone('j1').subscribe({
      error: (e) => (error = e),
      complete: () => (completed = true)
    });

    // One minute in: still healthily polling, no verdict yet. This is the
    // case RxJS timeout() would NOT have caught -- it measures the gap
    // between emissions, and a stuck job emits "processing" on schedule.
    vi.advanceTimersByTime(60_000);
    expect(error).toBeNull();
    expect(completed).toBe(false);

    vi.advanceTimersByTime(ExportService.POLL_TIMEOUT_MS);
    expect(error).toBeInstanceOf(ExportPollTimeoutError);
  });

  it('stops polling the moment the subscription is torn down', () => {
    const service = serviceAlwaysReturning('processing');
    const statusSpy = vi.spyOn(service, 'status');

    const subscription = service.pollUntilDone('j1').subscribe();
    vi.advanceTimersByTime(ExportService.POLL_INTERVAL_MS * 3);
    const callsWhileSubscribed = statusSpy.mock.calls.length;
    expect(callsWhileSubscribed).toBeGreaterThan(0);

    subscription.unsubscribe();
    vi.advanceTimersByTime(ExportService.POLL_INTERVAL_MS * 10);

    // This is what takeUntilDestroyed buys the component: navigating away
    // must silence the interval, not merely stop anyone from reading it.
    expect(statusSpy.mock.calls.length).toBe(callsWhileSubscribed);
  });

  it('completes normally when the job finishes, emitting the terminal status', () => {
    const service = serviceAlwaysReturning('completed');
    const seen: ExportStatus[] = [];
    let completed = false;

    service.pollUntilDone('j1').subscribe({
      next: (s) => seen.push(s),
      complete: () => (completed = true)
    });
    vi.advanceTimersByTime(ExportService.POLL_INTERVAL_MS);

    expect(seen.map((s) => s.status)).toEqual(['completed']);
    expect(completed).toBe(true);
  });

  it('caps the poll below the backend TTL so it never waits on an unreachable job', () => {
    // ExportJobWorker.EXPORT_JOB_TTL_SECONDS is 3600 and download 410s past
    // expires_at, so polling beyond the hour can only ever fail. Pinned so a
    // future change to either side has to change both deliberately.
    expect(ExportService.POLL_TIMEOUT_MS).toBeLessThan(60 * 60 * 1000);
  });
});
