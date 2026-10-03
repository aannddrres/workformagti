import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ComplianceService } from '../../core/services/compliance.service';
import { MyReading } from '../../core/models/compliance';
import { formatKaDate } from '../../shared/ka-date';
import { detailRouteFor } from '../../shared/content-type-visuals';

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

  // A confirmed item whose text changed since is owed again; it used to leave
  // this card saying everything was done (simulation, 2026-10-01).
  protected readonly unread = computed(() =>
    (this.readings() ?? []).filter((r) => r.status !== 'read' || r.changed_since_read)
  );
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

  /**
   * Routes to the item itself, for all three types.
   *
   * This used to send anything that was not an article to the /news LIST —
   * so a required news item or video landed the operator on a page that was
   * not the thing they were asked to read, and a video went to news outright.
   * Now that confirmation lives at the end of the material
   * (app-reading-confirm), landing on a list also means there is no way to
   * confirm from there. Uses the same detailRouteFor every other caller uses.
   */
  itemLink(reading: MyReading): string[] {
    return detailRouteFor(reading.reading.item_type, reading.reading.item_id) ?? ['/reading'];
  }
}
