import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { AdminExportDefinition, AdminExportFamily } from '../../core/models/admin-export';
import { AdminExportService } from '../../core/services/admin-export.service';
import { ExportPollTimeoutError, ExportService } from '../../core/services/export.service';

@Component({
  selector: 'app-admin-exports-page',
  standalone: true,
  templateUrl: './admin-exports-page.html'
})
export class AdminExportsPage {
  private readonly adminExport = inject(AdminExportService);
  private readonly exports = inject(ExportService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly from = signal('');
  protected readonly through = signal('');
  protected readonly running = signal<AdminExportFamily | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly success = signal<string | null>(null);

  protected readonly definitions: AdminExportDefinition[] = [
    { family: 'audit-ledger', title: 'აუდიტის სრული ჟურნალი', icon: 'fa-shield-halved', filename: 'audit-ledger.xlsx', description: 'მოქმედებები, IP, user agent და hash-chain-ის მთლიანობის ველები.' },
    { family: 'read-evidence', title: 'ოფიციალური გაცნობის მტკიცებულება', icon: 'fa-clipboard-check', filename: 'read-evidence.xlsx', description: 'სტატიის ოფიციალური დადასტურებები და სავალდებულო მასალის სტატუსები.' },
    { family: 'article-views', title: 'სტატიების გახსნის ისტორია', icon: 'fa-eye', filename: 'article-views.xlsx', description: 'უბრალო გახსნის ისტორია; ეს ოფიციალურ წაკითხვად არ ითვლება.' },
    { family: 'search-history', title: 'ძებნის ისტორია', icon: 'fa-magnifying-glass', filename: 'search-history.xlsx', description: 'ვინ რას ეძებდა და რამდენი შედეგი მიიღო.' },
    { family: 'quiz-attempts', title: 'Quiz მცდელობები', icon: 'fa-list-check', filename: 'quiz-attempts.xlsx', description: 'რეალურად შენახული ქულა, ვერსია, მცდელობის ნომერი და შედეგი.' },
    { family: 'change-events', title: 'ცვლილებები და უსაფრთხოების მოვლენები', icon: 'fa-user-lock', filename: 'change-events.xlsx', description: 'მომხმარებლის, კონტენტის, ადმინისტრაციული და უსაფრთხოების მოვლენები.' }
  ];

  protected setFrom(event: Event): void {
    this.from.set((event.target as HTMLInputElement).value);
  }

  protected setThrough(event: Event): void {
    this.through.set((event.target as HTMLInputElement).value);
  }

  protected submit(definition: AdminExportDefinition): void {
    if (this.running() !== null) return;
    if (this.from() && this.through() && this.from() > this.through()) {
      this.error.set('საწყისი თარიღი საბოლოო თარიღზე გვიან ვერ იქნება.');
      return;
    }
    this.running.set(definition.family);
    this.error.set(null);
    this.success.set(null);
    this.adminExport.submit(definition.family, this.from(), this.through())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (job) => this.waitAndDownload(job.job_id, definition),
        error: (error: HttpErrorResponse) => this.fail(
          error.error?.detail ?? 'ექსპორტის დაწყება ვერ მოხერხდა.')
      });
  }

  private waitAndDownload(jobId: string, definition: AdminExportDefinition): void {
    this.exports.pollUntilDone(jobId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (status) => {
        if (status.status === 'processing') {
          return;
        }
        if (status.status !== 'completed') {
          this.fail('ექსპორტის მომზადება შეწყდა. სცადეთ ხელახლა.');
          return;
        }
        this.exports.download(jobId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: (blob) => {
            this.downloadBlob(blob, definition.filename);
            this.running.set(null);
            this.success.set(`„${definition.title}“ მომზადდა და ჩამოიტვირთა.`);
          },
          error: () => this.fail('მომზადებული ფაილის ჩამოტვირთვა ვერ მოხერხდა.')
        });
      },
      error: (error) => this.fail(error instanceof ExportPollTimeoutError
        ? 'ფაილის მომზადებას მოსალოდნელზე მეტი დრო დასჭირდა. სცადეთ ხელახლა.'
        : 'ექსპორტის სტატუსის შემოწმება ვერ მოხერხდა.')
    });
  }

  private fail(message: string): void {
    this.running.set(null);
    this.error.set(message);
  }

  private downloadBlob(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
    URL.revokeObjectURL(url);
  }
}
