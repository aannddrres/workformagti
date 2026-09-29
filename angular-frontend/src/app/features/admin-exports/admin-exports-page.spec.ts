import { TestBed } from '@angular/core/testing';
import { Subject, of } from 'rxjs';
import { vi } from 'vitest';
import { provideTranslateService } from '@ngx-translate/core';
import { AdminExportService } from '../../core/services/admin-export.service';
import { ExportService } from '../../core/services/export.service';
import { ExportStatus } from '../../core/models/export';
import { AdminExportsPage } from './admin-exports-page';

describe('AdminExportsPage', () => {
  it('keeps waiting during processing, then offers a new attempt after failure', async () => {
    const statuses = new Subject<ExportStatus>();
    const submit = vi.fn(() => of({ job_id: 'job-1' }));
    const download = vi.fn(() => of(new Blob()));
    await TestBed.configureTestingModule({
      imports: [AdminExportsPage],
      providers: [
        provideTranslateService({ lang: 'ka', fallbackLang: 'ka' }),
        { provide: AdminExportService, useValue: { submit } },
        { provide: ExportService, useValue: { pollUntilDone: () => statuses.asObservable(), download } }
      ]
    }).compileComponents();

    const fixture = TestBed.createComponent(AdminExportsPage);
    fixture.detectChanges();
    // The first export card's button; the date fields above have buttons of their own.
    const button: HTMLButtonElement = fixture.nativeElement.querySelector('article button');
    button.click();
    statuses.next({ job_id: 'job-1', status: 'processing' });
    fixture.detectChanges();

    expect(button.disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    expect(download).not.toHaveBeenCalled();

    statuses.next({ job_id: 'job-1', status: 'failed' });
    fixture.detectChanges();
    expect(button.disabled).toBe(false);
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('სცადეთ ხელახლა');
    expect(download).not.toHaveBeenCalled();
    button.click();
    expect(submit).toHaveBeenCalledTimes(2);
  });
});
