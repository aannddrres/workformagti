import { Component, inject, signal } from '@angular/core';
import { BroadcastAnnouncement, BroadcastPriority } from '../../core/models/broadcast';
import { BroadcastService } from '../../core/services/broadcast.service';
import { formatKaDateTime } from '../ka-date';

@Component({
  selector: 'app-broadcast-banner',
  standalone: true,
  templateUrl: './broadcast-banner.html'
})
export class BroadcastBanner {
  private readonly broadcasts = inject(BroadcastService);

  protected readonly announcements = signal<BroadcastAnnouncement[]>([]);
  protected readonly loadFailed = signal(false);
  protected readonly dateTime = formatKaDateTime;

  constructor() {
    this.broadcasts.active().subscribe({
      next: (items) => this.announcements.set(items),
      error: () => this.loadFailed.set(true)
    });
  }

  protected priorityLabel(priority: BroadcastPriority): string {
    return ({ NORMAL: 'ჩვეულებრივი', IMPORTANT: 'მნიშვნელოვანი', CRITICAL: 'კრიტიკული' })[priority];
  }

  protected cardClass(priority: BroadcastPriority): string {
    return ({
      NORMAL: 'border-blue-200 bg-blue-50/80 text-blue-950 dark:border-blue-900/60 dark:bg-blue-950/30 dark:text-blue-100',
      IMPORTANT: 'border-amber-300 bg-amber-50/90 text-amber-950 dark:border-amber-800/70 dark:bg-amber-950/30 dark:text-amber-100',
      CRITICAL: 'border-red-300 bg-red-50 text-red-950 dark:border-red-800/70 dark:bg-red-950/35 dark:text-red-100'
    })[priority];
  }

  protected icon(priority: BroadcastPriority): string {
    return priority === 'CRITICAL' ? 'fa-triangle-exclamation'
      : priority === 'IMPORTANT' ? 'fa-circle-exclamation' : 'fa-circle-info';
  }
}
