import { vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { REFRESH_ON_REFUSAL_MS, accessRefreshInterceptor } from './access-refresh.interceptor';
import { UserProfileService } from '../auth/user-profile.service';

/**
 * A 403 means the screen's idea of the person's rights is out of date --
 * typically an administrator changed them after sign-in. The interceptor asks
 * UserProfileService to re-read them, so the menu follows the server.
 */
describe('accessRefreshInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let refreshAccess: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    refreshAccess = vi.fn();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([accessRefreshInterceptor])),
        provideHttpClientTesting(),
        { provide: UserProfileService, useValue: { refreshAccess } }
      ]
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  function fail(url: string, status: number): void {
    http.get(url).subscribe({ error: () => undefined });
    backend.expectOne(url).flush({ detail: 'x' }, { status, statusText: 'x' });
  }

  it('re-reads the rights when the server refuses, throttled', () => {
    fail('/api/admin/articles/stale', 403);
    expect(refreshAccess).toHaveBeenCalledWith(REFRESH_ON_REFUSAL_MS);
  });

  it('leaves every other failure alone', () => {
    fail('/api/articles', 500);
    fail('/api/articles', 404);
    expect(refreshAccess).not.toHaveBeenCalled();
  });

  it('does not answer a refused re-read with another re-read', () => {
    fail('/api/me/effective-access', 403);
    expect(refreshAccess).not.toHaveBeenCalled();
  });

  it('still hands the error to the caller', () => {
    const errors: number[] = [];
    http.get('/api/x').subscribe({ error: (e) => errors.push(e.status) });
    backend.expectOne('/api/x').flush({}, { status: 403, statusText: 'Forbidden' });
    expect(errors).toEqual([403]);
  });
});
