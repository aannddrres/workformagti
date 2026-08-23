import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ContentTrashService } from './content-trash.service';

describe('ContentTrashService', () => {
  let service: ContentTrashService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ContentTrashService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('uses separate list, restore and purge contracts', () => {
    service.list().subscribe();
    const list = http.expectOne('/api/content-trash');
    expect(list.request.method).toBe('GET');
    list.flush([]);

    service.restore('article', 42).subscribe();
    const restore = http.expectOne('/api/content-trash/article/42/restore');
    expect(restore.request.method).toBe('POST');
    restore.flush({ detail: 'ok' });

    service.purge('article', 42).subscribe();
    const purge = http.expectOne('/api/content-trash/article/42');
    expect(purge.request.method).toBe('DELETE');
    purge.flush({ detail: 'ok' });
  });
});
