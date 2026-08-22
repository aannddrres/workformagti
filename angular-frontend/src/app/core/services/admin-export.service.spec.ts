import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminExportService } from './admin-export.service';

describe('AdminExportService', () => {
  let service: AdminExportService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(AdminExportService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('submits the selected family with optional date filters and no payload', () => {
    service.submit('article-views', '2026-08-01', '2026-08-22').subscribe();

    const request = http.expectOne(
      (req) => req.url === '/api/admin/exports/article-views'
        && req.params.get('from') === '2026-08-01'
        && req.params.get('through') === '2026-08-22'
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    request.flush({ job_id: 'job-1' });
  });
});
