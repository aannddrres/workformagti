import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ReminderService } from './reminder.service';

describe('ReminderService', () => {
  let service: ReminderService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ReminderService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads only the current user inbox with bounded pagination', () => {
    service.inbox(2, 20).subscribe();
    const request = http.expectOne((value) => value.url === '/api/reminders');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('20');
    request.flush({ items: [], page: 2, size: 20, total_elements: 0, total_pages: 0 });
  });

  it('marks one reminder read without sending content', () => {
    service.markRead(17).subscribe();
    const request = http.expectOne('/api/reminders/17/read');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({});
    request.flush({});
  });

  it('manual delivery has no free-text request body', () => {
    service.sendManual(42).subscribe();
    const request = http.expectOne('/api/reminders/users/42/send');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({});
    request.flush({});
  });
});
