import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  inject,
  Injector,
  input,
  signal,
  viewChild
} from '@angular/core';

let nextId = 0;

/**
 * The "⋮" at the end of a table row (UI audit bug 22, plan §17 "one primary
 * action, the rest in a kebab menu"). The row's one visible action sits
 * beside it; everything else, destructive actions included, is inside.
 *
 * Before this the admin tables had four shapes for the same thing: a menu
 * alone (articles), three bare icons (news, videos, users), two bordered text
 * buttons (categories) and a red text link (leaders).
 *
 * The items are the caller's own `<button class="row-menu-item">`s, projected
 * as they are, so a test or a screen reader still finds each one as a button
 * by its name. It is a disclosure, not an ARIA menu, for the same reason.
 *
 * The panel is `position: fixed` so the table's scrolling container cannot
 * clip it at the last row; it closes on scroll instead of drifting.
 */
@Component({
  selector: 'app-row-menu',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'inline-flex',
    '(document:click)': 'onDocumentClick($event)',
    '(document:keydown.escape)': 'closeAndFocus()',
    '(window:resize)': 'close()'
  },
  template: `
    <button
      #trigger
      type="button"
      class="icon-button"
      [attr.aria-label]="label()"
      [attr.title]="label()"
      [attr.aria-expanded]="open()"
      [attr.aria-controls]="panelId"
      (click)="toggle()"
    >
      <i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i>
    </button>
    @if (open()) {
      <div
        #panel
        [id]="panelId"
        class="row-menu-panel"
        [style.top.px]="top()"
        [style.right.px]="right()"
        (click)="closeAndFocus()"
        (keydown)="onPanelKeydown($event)"
      >
        <ng-content />
      </div>
    }
  `
})
export class RowMenu {
  readonly label = input.required<string>();

  protected readonly panelId = `row-menu-${++nextId}`;
  protected readonly open = signal(false);
  protected readonly top = signal(0);
  protected readonly right = signal(0);

  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly injector = inject(Injector);
  private readonly trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');
  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');

  constructor() {
    // Any scrolling container, not only the window: the tables scroll inside
    // their own wrappers. Captured, because scroll does not bubble.
    const onScroll = (event: Event) => {
      if (this.open() && !this.panel()?.nativeElement.contains(event.target as Node)) this.close();
    };
    document.addEventListener('scroll', onScroll, true);
    inject(DestroyRef).onDestroy(() => document.removeEventListener('scroll', onScroll, true));
  }

  protected toggle(): void {
    if (this.open()) {
      this.close();
      return;
    }
    const rect = this.trigger().nativeElement.getBoundingClientRect();
    this.top.set(rect.bottom + 4);
    this.right.set(document.documentElement.clientWidth - rect.right);
    this.open.set(true);
    afterNextRender(() => {
      const panel = this.panel()?.nativeElement;
      if (!panel) return;
      // Near the bottom of the window it opens upwards instead.
      const height = panel.getBoundingClientRect().height;
      if (rect.bottom + 4 + height > window.innerHeight && rect.top - 4 - height > 0) {
        this.top.set(rect.top - 4 - height);
      }
      this.items()[0]?.focus();
    }, { injector: this.injector });
  }

  close(): void {
    this.open.set(false);
  }

  /**
   * Also what an item click ends with: focus goes back to the "⋮" before a
   * dialog the item opened records where to return it, since the item itself
   * is about to leave the page.
   */
  protected closeAndFocus(): void {
    if (!this.open()) return;
    this.close();
    this.trigger().nativeElement.focus();
  }

  protected onDocumentClick(event: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
      this.close();
    }
  }

  /** Arrow keys walk the items; Tab leaves the menu and closes it. */
  protected onPanelKeydown(event: KeyboardEvent): void {
    const items = this.items();
    const current = items.indexOf(document.activeElement as HTMLButtonElement);
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const step = event.key === 'ArrowDown' ? 1 : -1;
      items[(current + step + items.length) % items.length]?.focus();
    } else if (event.key === 'Tab') {
      this.close();
    }
  }

  private items(): HTMLButtonElement[] {
    const panel = this.panel()?.nativeElement;
    return panel ? Array.from(panel.querySelectorAll<HTMLButtonElement>('button:not([disabled])')) : [];
  }
}
