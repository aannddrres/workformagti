import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { BroadcastAnnouncement, BroadcastPriority } from '../../core/models/broadcast';
import { BroadcastService } from '../../core/services/broadcast.service';
import { formatKaDateTime } from '../../shared/ka-date';

function toLocalInput(date: Date): string {
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

@Component({
  selector: 'app-admin-broadcasts-page',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './admin-broadcasts-page.html'
})
export class AdminBroadcastsPage {
  private readonly broadcastService = inject(BroadcastService);
  private readonly formBuilder = inject(NonNullableFormBuilder);

  protected readonly items = signal<BroadcastAnnouncement[]>([]);
  protected readonly historyPage = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly totalItems = signal(0);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly endingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly success = signal<string | null>(null);
  protected readonly dateTime = formatKaDateTime;
  protected readonly minEndTime = toLocalInput(new Date(Date.now() + 60_000));

  protected readonly form = this.formBuilder.group({
    message: ['', [Validators.required, Validators.maxLength(2000)]],
    priority: ['NORMAL' as BroadcastPriority, Validators.required],
    endsAt: [toLocalInput(new Date(Date.now() + 4 * 60 * 60_000)), Validators.required]
  });

  constructor() {
    this.loadHistory();
  }

  protected loadHistory(page = this.historyPage()): void {
    this.loading.set(true);
    this.error.set(null);
    this.broadcastService.history(page).subscribe({
      next: (history) => {
        this.items.set(history.items);
        this.historyPage.set(history.page);
        this.totalPages.set(history.total_pages);
        this.totalItems.set(history.total_items);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(error?.error?.detail ?? 'განცხადებების ისტორიის ჩატვირთვა ვერ მოხერხდა.');
        this.loading.set(false);
      }
    });
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const endsAt = new Date(value.endsAt);
    if (Number.isNaN(endsAt.getTime()) || endsAt.getTime() <= Date.now()) {
      this.error.set('დასრულების დრო მომავალში უნდა იყოს.');
      return;
    }

    this.saving.set(true);
    this.error.set(null);
    this.success.set(null);
    this.broadcastService.publish({
      message: value.message.trim(),
      priority: value.priority,
      ends_at: endsAt.toISOString()
    }).subscribe({
      next: (created) => {
        this.historyPage.set(0);
        this.items.update((items) => [created, ...items].slice(0, 50));
        this.totalItems.update((count) => count + 1);
        this.form.controls.message.reset('');
        this.saving.set(false);
        this.success.set('საერთო ინფორმაცია გამოქვეყნდა.');
      },
      error: (error) => {
        this.error.set(error?.error?.detail ?? 'განცხადების გამოქვეყნება ვერ მოხერხდა.');
        this.saving.set(false);
      }
    });
  }

  protected endEarly(item: BroadcastAnnouncement): void {
    if (!item.can_end_early || !window.confirm('ნამდვილად გსურთ ამ განცხადების დროზე ადრე დასრულება?')) {
      return;
    }
    this.endingId.set(item.id);
    this.error.set(null);
    this.success.set(null);
    this.broadcastService.endEarly(item.id).subscribe({
      next: (updated) => {
        this.items.update((items) => items.map((value) => value.id === updated.id ? updated : value));
        this.endingId.set(null);
        this.success.set('განცხადება დასრულდა და მომხმარებლებთან აღარ გამოჩნდება.');
      },
      error: (error) => {
        this.error.set(error?.error?.detail ?? 'განცხადების დასრულება ვერ მოხერხდა.');
        this.endingId.set(null);
      }
    });
  }

  protected previousPage(): void {
    if (this.historyPage() > 0) this.loadHistory(this.historyPage() - 1);
  }

  protected nextPage(): void {
    if (this.historyPage() + 1 < this.totalPages()) this.loadHistory(this.historyPage() + 1);
  }

  protected priorityLabel(priority: BroadcastPriority): string {
    return ({ NORMAL: 'ჩვეულებრივი', IMPORTANT: 'მნიშვნელოვანი', CRITICAL: 'კრიტიკული' })[priority];
  }

  protected statusLabel(item: BroadcastAnnouncement): string {
    return ({ active: 'აქტიური', expired: 'ვადა გასულია', ended: 'დროზე ადრე დასრულებული' })[item.status];
  }
}
