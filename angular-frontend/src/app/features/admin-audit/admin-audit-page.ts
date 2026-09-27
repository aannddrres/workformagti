import { computed, Component, inject, signal } from '@angular/core';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { AuditService } from '../../core/services/audit.service';
import { AuthService } from '../../core/auth/auth.service';
import { PortalDialog } from '../../shared/portal-dialog/portal-dialog';
import { AuditChainHealth, AuditLogEntry, AuditVerifyResult } from '../../core/models/audit';
import {
  categoryBadge,
  filterFromSearch,
  formatAuditActor,
  formatAuditAction,
  formatAuditItemType,
  formatAuditItemName,
  parseAuditDetails,
  parseUserAgent,
  withCategoryToken
} from '../../shared/audit-format';
import { formatKaDateTime } from '../../shared/ka-date';
import { auditDatePreset } from './audit-date-presets';

const PAGE_SIZE = 50;

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
 * - The target product boundary makes this entire page SYSTEM_ADMIN-only.
 * - The CSV export button (`#btn-export-audit-csv`) has no click handler
 *   anywhere in static/js -- a dead control in the Python original. The
 *   backend endpoint it's unambiguously labeled for (`GET
 *   /api/audit-logs/export`) already exists and works, so it's wired up for
 *   real here rather than ported as another dead button.
 */
@Component({
  selector: 'app-admin-audit-page',
  standalone: true,
  imports: [TranslatePipe, PortalDialog],
  templateUrl: './admin-audit-page.html'
})
export class AdminAuditPage {
  private readonly auth = inject(AuthService);

  /**
   * Kept as a defensive display flag for stale tabs during a permission
   * revocation; route and API enforcement both reject non-system-admins.
   */
  protected readonly isManager = computed(() => this.auth.currentUser()?.role === 'manager');
  private readonly auditService = inject(AuditService);
  private readonly translate = inject(TranslateService);

  protected readonly rows = signal<AuditLogEntry[]>([]);
  protected readonly total = signal(0);
  protected readonly loading = signal(true);
  protected readonly error = signal(false);
  protected readonly offset = signal(0);
  /** Server-side, because the page shows fifty rows of a much longer trail:
   *  reordering those fifty would look like reordering the log and would not
   *  be. Only the timestamp -- see AuditService.list. */
  protected readonly timeDirection = signal<'asc' | 'desc'>('desc');
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
  protected readonly actionLabel = (action: string | null | undefined) =>
    formatAuditAction(action, key => this.translate.instant(key));
  protected readonly actorLabel = (name: string | null | undefined) =>
    formatAuditActor(name, key => this.translate.instant(key));
  protected readonly itemTypeLabel = formatAuditItemType;
  protected readonly itemNameLabel = (type: string | null | undefined, name: string | null | undefined) =>
    formatAuditItemName(type, name, key => this.translate.instant(key));
  protected readonly formatTimestamp = formatKaDateTime;

  load(): void {
    this.loading.set(true);
    this.error.set(false);
    const filter = filterFromSearch(this.searchText(), this.startDate(), this.endDate());
    const requestId = ++this.requestSeq;
    this.auditService.list(filter, this.limit, this.offset(), this.timeDirection()).subscribe({
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

  toggleTimeOrder(): void {
    this.timeDirection.update((current) => (current === 'desc' ? 'asc' : 'desc'));
    this.offset.set(0);
    this.load();
  }

  timeAriaSort(): 'ascending' | 'descending' {
    return this.timeDirection() === 'asc' ? 'ascending' : 'descending';
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
    const range = auditDatePreset(days);
    this.startDate.set(range.start);
    this.endDate.set(range.end);
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
