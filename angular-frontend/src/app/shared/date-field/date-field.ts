import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  forwardRef,
  inject,
  input,
  linkedSignal,
  model,
  signal,
  viewChild
} from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { KA_MONTHS, KA_WEEKDAYS } from '../ka-date';
import { datePart, displayValue, isoDate, monthGrid, parseTyped, shiftDay, timePart } from './date-field-model';

const HOURS = Array.from({ length: 24 }, (_, hour) => String(hour).padStart(2, '0'));
const MINUTE_STEPS = Array.from({ length: 12 }, (_, step) => String(step * 5).padStart(2, '0'));

/**
 * The portal's date field (owner decision კ6), in place of the nine native
 * `<input type="date">` / `datetime-local` controls.
 *
 * Chrome draws a native one in the operating system's language and order --
 * on the machines here that was `mm/dd/yyyy` with English month names, and a
 * 12-hour AM/PM clock on the two that take a time -- beside a portal that
 * writes `29 სექ. 2026` everywhere else. This one is typed as `29.09.2026`
 * (`29.09.2026 14:30` with a time), opens a Georgian calendar, and stores the
 * same strings the native inputs did, so no caller's parsing changed.
 *
 * It binds either way: `[(value)]` for the signal-driven drawers, or
 * `formControlName` for the broadcast form.
 */
@Component({
  selector: 'app-date-field',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe],
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => DateField), multi: true }],
  host: {
    class: 'block',
    '(document:click)': 'onDocumentClick($event)',
    '(keydown.escape)': 'onEscape($event)',
    '(window:resize)': 'close()'
  },
  template: `
    <div class="relative">
      <input
        #field
        type="text"
        autocomplete="off"
        class="field-control w-full pr-10"
        [class.border-red-500]="invalidState()"
        [class.dark:border-red-400]="invalidState()"
        [attr.id]="inputId()"
        [attr.aria-label]="ariaLabel()"
        [attr.aria-invalid]="invalidState() || null"
        [attr.aria-describedby]="describedBy()"
        [placeholder]="(withTime() ? 'shared.date_field.placeholder_time' : 'shared.date_field.placeholder') | translate"
        [value]="text()"
        [disabled]="disabled()"
        (input)="onTyping($event)"
        (blur)="commitTyped()"
        (keydown.enter)="commitTyped(); $event.preventDefault()"
        (keydown.alt.arrowdown)="openCalendar(); $event.preventDefault()"
      />
      <button
        #toggleButton
        type="button"
        class="absolute inset-y-0 right-0 flex w-10 items-center justify-center rounded-r-md text-slate-600 hover:text-slate-900 disabled:opacity-50 dark:text-slate-400 dark:hover:text-slate-100"
        [attr.aria-label]="'shared.date_field.open' | translate"
        [attr.aria-expanded]="open()"
        [disabled]="disabled()"
        (click)="toggle()"
      ><i class="fa-regular fa-calendar" aria-hidden="true"></i></button>
    </div>
    @if (open()) {
      <div
        #panel
        role="dialog"
        [attr.aria-label]="'shared.date_field.dialog_label' | translate"
        class="date-panel"
        [style.top.px]="top()"
        [style.left.px]="left()"
      >
        <div class="flex items-center justify-between gap-2">
          <button type="button" class="icon-button" [attr.aria-label]="'shared.date_field.previous_month' | translate" (click)="shiftMonth(-1)">
            <i class="fa-solid fa-chevron-left" aria-hidden="true"></i>
          </button>
          <p class="text-sm font-bold text-slate-900 dark:text-slate-100" aria-live="polite">{{ monthTitle() }}</p>
          <button type="button" class="icon-button" [attr.aria-label]="'shared.date_field.next_month' | translate" (click)="shiftMonth(1)">
            <i class="fa-solid fa-chevron-right" aria-hidden="true"></i>
          </button>
        </div>
        <table role="grid" class="mt-2 w-full border-collapse text-center" (keydown)="onGridKeydown($event)">
          <thead>
            <tr>
              @for (weekday of weekdays; track weekday.short) {
                <th scope="col" class="h-8 text-xs font-semibold text-slate-600 dark:text-slate-400" [attr.abbr]="weekday.full">{{ weekday.short }}</th>
              }
            </tr>
          </thead>
          <tbody>
            @for (week of weeks(); track $index) {
              <tr>
                @for (day of week; track day.iso) {
                  <td class="p-0.5">
                    <button
                      type="button"
                      class="date-day"
                      [class.date-day-outside]="!day.inMonth"
                      [class.date-day-today]="day.iso === today"
                      [attr.aria-selected]="day.iso === selectedDay()"
                      [attr.aria-label]="day.label"
                      [attr.data-day]="day.iso"
                      [attr.tabindex]="day.iso === focusDay() ? 0 : -1"
                      [disabled]="day.disabled"
                      (click)="pick(day.iso)"
                    >{{ day.day }}</button>
                  </td>
                }
              </tr>
            }
          </tbody>
        </table>
        @if (withTime()) {
          <div class="mt-3 flex items-center gap-2 border-t border-slate-200 pt-3 dark:border-slate-700">
            <span class="text-sm font-semibold text-slate-700 dark:text-slate-200">{{ 'shared.date_field.time' | translate }}</span>
            <select class="field-control min-h-9 py-1" [attr.aria-label]="'shared.date_field.hour' | translate" [value]="hour()" (change)="setTime($any($event.target).value, minute())">
              @for (option of hours; track option) { <option [value]="option">{{ option }}</option> }
            </select>
            <span aria-hidden="true">:</span>
            <select class="field-control min-h-9 py-1" [attr.aria-label]="'shared.date_field.minute' | translate" [value]="minute()" (change)="setTime(hour(), $any($event.target).value)">
              @for (option of minutes(); track option) { <option [value]="option">{{ option }}</option> }
            </select>
          </div>
        }
        <div class="mt-3 flex items-center justify-between gap-2 border-t border-slate-200 pt-3 dark:border-slate-700">
          <button type="button" class="quiet-button" [disabled]="min() !== null && today < minDay()!" (click)="pick(today)">{{ 'shared.date_field.today' | translate }}</button>
          <div class="flex gap-1">
            <button type="button" class="quiet-button" (click)="clear()">{{ 'shared.date_field.clear' | translate }}</button>
            @if (withTime()) {
              <button type="button" class="secondary-button min-h-9 py-1.5" (click)="closeAndFocus()">{{ 'shared.date_field.done' | translate }}</button>
            }
          </div>
        </div>
      </div>
    }
  `
})
export class DateField implements ControlValueAccessor {
  /** `2026-09-29`, or `2026-09-29T14:30` with a time; `''` when empty. */
  readonly value = model<string>('');
  readonly withTime = input(false);
  /** The earliest day the calendar offers, in the value's own shape. */
  readonly min = input<string | null>(null);
  readonly inputId = input<string | null>(null);
  readonly ariaLabel = input<string | null>(null);
  readonly describedBy = input<string | null>(null);
  readonly invalid = input(false);

  protected readonly weekdays = KA_WEEKDAYS;
  protected readonly hours = HOURS;
  protected readonly today = isoDate(new Date());

  protected readonly disabled = signal(false);
  protected readonly open = signal(false);
  protected readonly top = signal(0);
  protected readonly left = signal(0);
  /** What the field shows: follows the value until someone types. */
  protected readonly text = linkedSignal(() => displayValue(this.value(), this.withTime()));
  protected readonly typedInvalid = signal(false);
  protected readonly invalidState = computed(() => this.invalid() || this.typedInvalid());

  protected readonly selectedDay = computed(() => datePart(this.value()));
  protected readonly minDay = computed(() => datePart(this.min()));
  protected readonly hour = computed(() => (timePart(this.value()) ?? '09:00').slice(0, 2));
  protected readonly minute = computed(() => (timePart(this.value()) ?? '09:00').slice(3, 5));
  /** Five-minute steps, plus the stored minute when it is not one of them. */
  protected readonly minutes = computed(() => {
    const current = this.minute();
    return MINUTE_STEPS.includes(current) ? MINUTE_STEPS : [...MINUTE_STEPS, current].sort();
  });

  /** The day keyboard focus is on; it decides which month is showing. */
  protected readonly focusDay = signal(this.today);
  protected readonly weeks = computed(() => {
    const [year, month] = this.focusDay().split('-').map(Number);
    return monthGrid(year, month - 1, this.minDay());
  });
  protected readonly monthTitle = computed(() => {
    const [year, month] = this.focusDay().split('-').map(Number);
    return `${KA_MONTHS[month - 1]} ${year}`;
  });

  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly injector = inject(Injector);
  private readonly field = viewChild.required<ElementRef<HTMLInputElement>>('field');
  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');

  private onChange: (value: string) => void = () => {};
  private onTouched: () => void = () => {};

  constructor() {
    const onScroll = (event: Event) => {
      if (this.open() && !this.panel()?.nativeElement.contains(event.target as Node)) this.close();
    };
    document.addEventListener('scroll', onScroll, true);
    inject(DestroyRef).onDestroy(() => document.removeEventListener('scroll', onScroll, true));
  }

  writeValue(value: string | null): void {
    this.value.set(value ?? '');
    this.typedInvalid.set(false);
  }

  registerOnChange(fn: (value: string) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(disabled: boolean): void {
    this.disabled.set(disabled);
  }

  protected onTyping(event: Event): void {
    this.text.set((event.target as HTMLInputElement).value);
    this.typedInvalid.set(false);
  }

  /** On leaving the field or Enter: a date is taken, anything else is marked. */
  protected commitTyped(): void {
    const parsed = parseTyped(this.text(), this.withTime(), timePart(this.value()) ?? '09:00');
    if (parsed === null) {
      this.typedInvalid.set(true);
      return;
    }
    this.typedInvalid.set(false);
    // Leaving the field unchanged is not a change: the audit list reloads on one.
    if (parsed === this.value()) {
      this.text.set(displayValue(parsed, this.withTime()));
    } else {
      this.set(parsed);
    }
    this.onTouched();
  }

  protected toggle(): void {
    if (this.open()) this.close();
    else this.openCalendar();
  }

  protected openCalendar(): void {
    const start = this.selectedDay() ?? (this.minDay() && this.minDay()! > this.today ? this.minDay()! : this.today);
    this.focusDay.set(start);
    const rect = this.field().nativeElement.getBoundingClientRect();
    this.top.set(rect.bottom + 4);
    this.left.set(rect.left);
    this.open.set(true);
    afterNextRender(() => {
      const panel = this.panel()?.nativeElement;
      if (!panel) return;
      const box = panel.getBoundingClientRect();
      if (box.bottom > window.innerHeight && rect.top - 4 - box.height > 0) {
        this.top.set(rect.top - 4 - box.height);
      }
      if (box.right > document.documentElement.clientWidth - 8) {
        this.left.set(Math.max(8, document.documentElement.clientWidth - 8 - box.width));
      }
      this.focusGridDay();
    }, { injector: this.injector });
  }

  close(): void {
    this.open.set(false);
  }

  protected closeAndFocus(): void {
    if (!this.open()) return;
    this.close();
    this.field().nativeElement.focus();
  }

  /**
   * Stopped here, while the calendar is open, because a drawer around this
   * field closes on Escape from the document -- one key press would otherwise
   * close the calendar and ask about discarding the whole form.
   */
  protected onEscape(event: Event): void {
    if (!this.open()) return;
    event.stopPropagation();
    this.closeAndFocus();
  }

  protected onDocumentClick(event: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) this.close();
  }

  protected shiftMonth(months: number): void {
    this.focusDay.set(shiftDay(this.focusDay(), 0, months));
  }

  protected pick(day: string): void {
    this.typedInvalid.set(false);
    if (this.withTime()) {
      this.set(`${day}T${this.hour()}:${this.minute()}`);
      this.focusDay.set(day);
      return;
    }
    this.set(day);
    this.closeAndFocus();
  }

  protected setTime(hour: string, minute: string): void {
    const day = this.selectedDay() ?? this.focusDay();
    this.set(`${day}T${hour}:${minute}`);
  }

  protected clear(): void {
    this.typedInvalid.set(false);
    this.set('');
    this.closeAndFocus();
  }

  /** The grid's keys, as the WAI date-picker pattern has them. */
  protected onGridKeydown(event: KeyboardEvent): void {
    const moves: Record<string, [number, number]> = {
      ArrowLeft: [-1, 0],
      ArrowRight: [1, 0],
      ArrowUp: [-7, 0],
      ArrowDown: [7, 0],
      PageUp: [0, event.shiftKey ? -12 : -1],
      PageDown: [0, event.shiftKey ? 12 : 1]
    };
    const current = this.focusDay();
    const weekday = (new Date(`${current}T00:00`).getDay() + 6) % 7;
    if (event.key === 'Home') moves['Home'] = [-weekday, 0];
    if (event.key === 'End') moves['End'] = [6 - weekday, 0];
    const move = moves[event.key];
    if (!move) return;
    event.preventDefault();
    this.focusDay.set(shiftDay(current, move[0], move[1]));
    // Focus moves with the key press when the day's button is already drawn,
    // and again once the grid redraws -- into another month it is a new one.
    this.focusGridDay();
    afterNextRender(() => this.focusGridDay(), { injector: this.injector });
  }

  private focusGridDay(): void {
    this.panel()?.nativeElement.querySelector<HTMLButtonElement>(`[data-day="${this.focusDay()}"]`)?.focus();
  }

  private set(value: string): void {
    this.value.set(value);
    this.text.set(displayValue(value, this.withTime()));
    this.onChange(value);
  }
}
