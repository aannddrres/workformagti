import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ComplianceService } from '../../core/services/compliance.service';
import { MyReading } from '../../core/models/compliance';
import { formatKaDate } from '../../shared/ka-date';

/**
 * Port of fetchNotificationsCount's two dashboard-facing widgets
 * (frontend_api.js:172-227) -- stat card + top-3 "must read" list, off one
 * GET /api/compliance/my-readings call. The original's `#stat-overdue`
 * badge computed an overdue count but never wired it to the DOM (dead
 * half-feature); this port completes that wiring instead of reproducing
 * the bug, since it's a fresh page with no external caller depending on
 * the old broken behavior.
 */
@Component({
  selector: 'app-mandatory-reading-widget',
  standalone: true,
  imports: [RouterLink, TranslatePipe],
  templateUrl: './mandatory-reading-widget.html'
})
export class MandatoryReadingWidget {
  private readonly complianceService = inject(ComplianceService);
  private readonly translate = inject(TranslateService);

  protected readonly readings = signal<MyReading[] | null>(null);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly unread = computed(() => (this.readings() ?? []).filter((r) => r.status !== 'read'));
  protected readonly overdueCount = computed(() => this.unread().filter((r) => r.is_overdue).length);
  protected readonly topThree = computed(() =>
    [...this.unread()].sort((a, b) => this.dueTime(a) - this.dueTime(b)).slice(0, 3)
  );

  constructor() {
    this.complianceService.myReadings().subscribe({
      next: (readings) => this.readings.set(readings),
      error: () => this.errorMessage.set(this.translate.instant('dashboard.mandatory_reading.load_error'))
    });
  }

  private dueTime(reading: MyReading): number {
    return reading.reading.due_date ? new Date(reading.reading.due_date).getTime() : Infinity;
  }

  dueLabel(reading: MyReading): string {
    return reading.reading.due_date ? formatKaDate(reading.reading.due_date) : '';
  }

  itemLink(reading: MyReading): string[] {
    return reading.reading.item_type === 'article' ? ['/article', String(reading.reading.item_id)] : ['/news'];
  }
}
