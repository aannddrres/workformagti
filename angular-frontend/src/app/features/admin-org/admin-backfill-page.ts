import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { OrgBackfillReport, PolicyShadowSnapshot } from '../../core/models/org-admin';
import { OrgAdminService } from '../../core/services/org-admin.service';

@Component({
  selector: 'app-admin-backfill-page',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './admin-backfill-page.html'
})
export class AdminBackfillPage {
  private readonly orgAdmin = inject(OrgAdminService);

  protected readonly shadow = signal<PolicyShadowSnapshot | null>(null);
  protected readonly report = signal<OrgBackfillReport | null>(null);
  protected readonly loading = signal(true);
  protected readonly applying = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly decisions = computed(() => Object.entries(this.shadow()?.decisions ?? {}));

  constructor() {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    forkJoin({ shadow: this.orgAdmin.policyShadow(), report: this.orgAdmin.backfillReport() }).subscribe({
      next: ({ shadow, report }) => {
        this.shadow.set(shadow);
        this.report.set(report);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(error?.error?.detail ?? 'Cutover-ის მტკიცებულებების ჩატვირთვა ვერ მოხერხდა.');
        this.loading.set(false);
      }
    });
  }

  protected apply(): void {
    this.applying.set(true);
    this.error.set(null);
    this.orgAdmin.applyBackfill().subscribe({
      next: (report) => {
        this.report.set(report);
        this.applying.set(false);
      },
      error: (error) => {
        this.error.set(error?.error?.detail ?? 'Backfill-ის გაშვება ვერ მოხერხდა.');
        this.applying.set(false);
      }
    });
  }
}
