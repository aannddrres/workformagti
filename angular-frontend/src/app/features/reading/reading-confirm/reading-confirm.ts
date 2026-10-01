import { Component, computed, inject, input, signal } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { ComplianceService } from '../../../core/services/compliance.service';
import { MyReading } from '../../../core/models/compliance';
import { QuizTakerModal } from '../quiz-taker-modal/quiz-taker-modal';
import { KaDatePipe } from '../../../shared/ka-date.pipe';

/**
 * The "I have read this" confirmation, placed at the END of the item the
 * operator is being asked to read.
 *
 * WHY IT MOVED HERE
 * Confirmation used to live only as a button on the /reading list row
 * (my-readings-page.html:99), while clicking the row navigated away to the
 * item. That produced two bad outcomes at once:
 *
 *   - Reading properly was punished: open the article, read it, then navigate
 *     back and find the row again to confirm.
 *   - Not reading was rewarded: one click on a list row satisfied a
 *     compliance obligation without the article ever being opened.
 *
 * For a portal whose whole purpose is provable procedure compliance, that is
 * the weakest possible arrangement, and it was a layout decision rather than
 * a bug. Putting the control after the content means the operator has at
 * least been taken through the material to reach it, and "read" starts to
 * describe something that happened.
 *
 * Renders nothing when the item is not a required reading for this user, so
 * it is safe to drop onto every detail page. Management roles receive an
 * empty list from /api/compliance/my-readings by design
 * (ComplianceController:107-109), so they simply never see it.
 */
@Component({
  selector: 'app-reading-confirm',
  standalone: true,
  imports: [TranslatePipe, QuizTakerModal, KaDatePipe],
  templateUrl: './reading-confirm.html'
})
export class ReadingConfirm {
  private readonly complianceService = inject(ComplianceService);

  readonly itemType = input.required<string>();
  readonly itemId = input.required<number>();

  /** null = still loading, or genuinely not a required reading for this user. */
  protected readonly reading = signal<MyReading | null>(null);
  protected readonly marking = signal(false);
  protected readonly failed = signal(false);
  protected readonly quizOpen = signal(false);
  /**
   * The obligation could not be determined at all (the my-readings request
   * failed). Distinct from "no obligation": rendering nothing in this case
   * would leave an operator who does owe this reading with no control and no
   * explanation — and since the list row no longer marks items read, no way
   * out either.
   */
  protected readonly lookupFailed = signal(false);

  /**
   * Confirmed, and nothing has changed since. A confirmed item whose text
   * changed afterwards is owed again: the page said "confirmed" with no way
   * to acknowledge the new text, and the server already records a receipt
   * for the new version when asked again (simulation, 2026-10-01).
   */
  protected readonly isRead = computed(() => this.reading()?.status === 'read' && !this.reading()?.changed_since_read);
  protected readonly isChanged = computed(() => this.reading()?.status === 'read' && this.reading()?.changed_since_read === true);
  protected readonly isOverdue = computed(() => this.reading()?.is_overdue === true);

  constructor() {
    this.load();
  }

  protected load(): void {
    // Loading is silent on purpose: for the majority of items this request
    // legitimately finds nothing, and a spinner for "you have no obligation
    // here" would be noise on every article in the knowledge base. A
    // *failure*, by contrast, is always shown — see lookupFailed.
    this.lookupFailed.set(false);
    this.complianceService.myReadings().subscribe({
      next: (readings) => {
        const match = readings.find(
          (r) => r.reading.item_type === this.itemType() && r.reading.item_id === this.itemId()
        );
        this.reading.set(match ?? null);
      },
      error: () => {
        this.reading.set(null);
        this.lookupFailed.set(true);
      }
    });
  }

  confirm(): void {
    const current = this.reading();
    if (!current || this.marking()) {
      return;
    }
    this.marking.set(true);
    this.failed.set(false);
    this.complianceService.markRead(current.reading.id).subscribe((result) => {
      this.marking.set(false);
      if (result.ok) {
        this.reading.set({ ...current, status: 'read', read_at: result.status.read_at, is_overdue: false, changed_since_read: false });
      } else if (result.quizRequired) {
        this.quizOpen.set(true);
      } else {
        // Surfaced, not swallowed -- the operator must not be left believing
        // an obligation is cleared when the server refused it.
        this.failed.set(true);
      }
    });
  }

  protected closeQuiz(): void {
    this.quizOpen.set(false);
  }

  protected onQuizPassed(): void {
    this.quizOpen.set(false);
    this.confirm();
  }
}
