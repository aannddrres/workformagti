import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { OrgStructure } from '../../core/models/org-admin';
import { OrgAdminService } from '../../core/services/org-admin.service';

@Component({
  selector: 'app-admin-org-page',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './admin-org-page.html'
})
export class AdminOrgPage {
  private readonly orgAdmin = inject(OrgAdminService);

  protected readonly structure = signal<OrgStructure | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.orgAdmin.structure().subscribe({
      next: (structure) => {
        this.structure.set(structure);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(error?.error?.detail ?? 'ორგანიზაციული სტრუქტურის ჩატვირთვა ვერ მოხერხდა.');
        this.loading.set(false);
      }
    });
  }
}
