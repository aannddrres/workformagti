import { computed, Component, inject, signal } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { AuditService } from '../../core/services/audit.service';
import { AuthService } from '../../core/auth/auth.service';
import { AuditChainHealth, AuditLogEntry, AuditVerifyResult } from '../../core/models/audit';
import {
  categoryBadge,
  filterFromSearch,
  formatAuditAction,
  formatAuditItemType,
  parseAuditDetails,
  parseUserAgent,
  withCategoryToken
} from '../../shared/audit-format';
import { formatKaDateTime } from '../../shared/ka-date';

const PAGE_SIZE = 50;

function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}

/**
 * Port of #admin-audit (base-layout.html:2345-2387) + its render layer
 * audit-dashboard.js (AuditDashboard) + the detail drawer
 * #audit-detail-panel (base-layout.html:2404-2413). Full rewrite against
 * signals, not a line translation.
 *
 * Backend (AuditLogController) was already complete before this slice --
 * this is a pure Angular build, same shape as TeamStatsPage/AdminStatsPage.
 *
 * Deliberate deviations from the Python original:
 * - Two native `<input type="date">` fields instead of a single flatpickr
 *   range-picker -- avoids pulling in a new JS dependency for one field,
 *   consistent with this port's "no wrapper library unless the feature
 *   genuinely needs it" convention (chart.js was justified by real charting
 *   needs; a date range is two native inputs).
 * - Technical action/item codes are preserved in the data and tooltip, but
 *   rendered as stable Georgian labels. Audit is a frequent operational
 *   screen and raw implementation codes are not useful primary copy.
 * - The manager role's dept-scoped, read-only view of this page
 *   (audit-dashboard.js's `isManager()` branches: hidden export/chain-health,
 *   a scope note) is unreachable through the Python UI today -- the whole
 *   "ადმინისტრირება" sidebar section is gated
 *   `data-required-role="admin,content_admin"` at its wrapping `<div>`,
 *   which hides all descendants regardless of the per-item re-show logic
 *   audit-dashboard.js layers on top. This route is gated the same way
 *   (`ADMIN_OR_CONTENT_ADMIN` on the parent `/admin` route), so those
 *   manager-only branches have no reachable caller here either -- not
 *   ported, matching actual live behavior rather than dead defensive code.
 * - The CSV export button (`#btn-export-audit-csv`) has no click handler
 *   anywhere in static/js -- a dead control in the Python original. The
 *   backend endpoint it's unambiguously labeled for (`GET
 *   /api/audit-logs/export`) already exists and works, so it's wired up for
 *   real here rather than ported as another dead button.
 */
@Component({
  selector: 'app-admin-audit-page',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './admin-audit-page.html'
})
export class AdminAuditPage {
  private readonly auth = inject(AuthService);

  /**
   * The backend serves MANAGER a department-scoped audit list
   * (AuditLogController:205-207) but refuses them export, verify and
   * chain-health outright — those use requireSystemAuditNonManager, bulk
   * egress and integrity tooling rather than the scoped read view.
   *
   * The Python original had exactly these branches (audit-dashboard.js's
   * isManager()); this port skipped them because /admin was role-gated and
   * a manager could never reach the page. Now that the route is permission
   * -driven they have a reachable caller again, so they are back: without
   * them a manager would be handed three controls that answer only 403.
   */
  protected readonly isManager = computed(() => this.auth.currentUser()?.role === 'manager');
  private readonly auditService = inject(AuditService);

  protected readonly rows = signal<AuditLogEntry[]>([]);
  protected readonly total = signal(0);
  protected readonly loading = signal(true);
  protected readonly error = signal(false);
  protected readonly offset = signal(0);
  protected readonly limit = PAGE_SIZE;

  protected readonly searchText = signal('');
  protected readonly startDate = signal<string | null>(null);
  protected readonly endDate = signal<string | null>(null);

  protected readonly chainHealth = signal<AuditChainHealth | null>(null);
  protected readonly chainHealthLoading = signal(true);
  protected readonly chainHealthError = signal(false);

  protected readonly selectedLog = signal<AuditLogEntry | null>(null);
  protected readonly verifyResult = signal<AuditVerifyResult | null>(null);
  protected readonly verifying = signal(false);
  protected readonly exporting = signal(false);

  private searchDebounce?: ReturnType<typeof setTimeout>;
  private requestSeq = 0;

  constructor() {
    this.load();
    this.loadChainHealth();
  }

  protected readonly categoryBadge = categoryBadge;
  protected readonly actionLabel = formatAuditAction;
  protected readonly itemTypeLabel = formatAuditItemType;
  protected readonly formatTimestamp = formatKaDateTime;

  load(): void {
    this.loading.set(true);
    this.error.set(false);
    const filter = filterFromSearch(this.searchText(), this.startDate(), this.endDate());
    const requestId = ++this.requestSeq;
    this.auditService.list(filter, this.limit, this.offset()).subscribe({
      next: (page) => {
        if (requestId !== this.requestSeq) return;
        this.rows.set(page.rows);
        this.total.set(page.total);
        this.loading.set(false);
      },
      error: () => {
        if (requestId !== this.requestSeq) return;
        this.error.set(true);
        this.loading.set(false);
      }
    });
  }

  private loadChainHealth(): void {
    if (this.isManager()) {
      // requireSystemAuditNonManager: a guaranteed 403. Skip the call rather
      // than fire it and render the resulting error state.
      this.chainHealthLoading.set(false);
      return;
    }
    this.chainHealthLoading.set(true);
    this.chainHealthError.set(false);
    this.auditService.chainHealth().subscribe({
      next: (health) => {
        this.chainHealth.set(health);
        this.chainHealthLoading.set(false);
      },
      error: () => {
        this.chainHealthError.set(true);
        this.chainHealthLoading.set(false);
      }
    });
  }

  reload(): void {
    this.offset.set(0);
    this.load();
  }

  refresh(): void {
    this.reload();
    this.loadChainHealth();
  }

  gotoOffset(next: number): void {
    this.offset.set(Math.max(0, next));
    this.load();
  }

  nextPage(): void {
    this.gotoOffset(this.offset() + this.limit);
  }

  prevPage(): void {
    this.gotoOffset(this.offset() - this.limit);
  }

  onSearchInput(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.searchText.set(value);
    clearTimeout(this.searchDebounce);
    this.searchDebounce = setTimeout(() => this.reload(), 300);
  }

  onCategoryChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.searchText.set(withCategoryToken(this.searchText(), value));
    this.reload();
  }

  onStartDateChange(event: Event): void {
    this.startDate.set((event.target as HTMLInputElement).value || null);
    this.reload();
  }

  onEndDateChange(event: Event): void {
    this.endDate.set((event.target as HTMLInputElement).value || null);
    this.reload();
  }

  setDatePreset(days: number): void {
    const end = new Date();
    const start = new Date();
    start.setDate(end.getDate() - days);
    this.startDate.set(isoDate(start));
    this.endDate.set(isoDate(end));
    this.reload();
  }

  openDetail(log: AuditLogEntry): void {
    this.selectedLog.set(log);
    this.verifyResult.set(null);
  }

  closeDetail(): void {
    this.selectedLog.set(null);
    this.verifyResult.set(null);
  }

  verifyRow(): void {
    const log = this.selectedLog();
    if (!log) return;
    this.verifying.set(true);
    this.auditService.verify(log.id).subscribe({
      next: (result) => {
        this.verifyResult.set(result);
        this.verifying.set(false);
      },
      error: () => {
        this.verifyResult.set({ status: 'error', hash_match: null, chain_match: null, row_hash: null, recomputed_hash: null });
        this.verifying.set(false);
      }
    });
  }

  exportCsv(): void {
    this.exporting.set(true);
    const filter = filterFromSearch(this.searchText(), this.startDate(), this.endDate());
    this.auditService.exportCsv(filter).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = 'audit_logs.csv';
        link.click();
        URL.revokeObjectURL(url);
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false)
    });
  }

  protected detailsView(log: AuditLogEntry) {
    return parseAuditDetails(log.details);
  }

  protected userAgentView(log: AuditLogEntry) {
    return parseUserAgent(log.user_agent);
  }

  protected pageFrom(): number {
    return this.total() ? this.offset() + 1 : 0;
  }

  protected pageTo(): number {
    return Math.min(this.offset() + this.limit, this.total());
  }
}
