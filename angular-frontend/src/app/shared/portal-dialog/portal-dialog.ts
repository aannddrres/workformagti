import {
  AfterViewInit,
  Directive,
  ElementRef,
  HostListener,
  OnDestroy,
  inject,
  input,
  output
} from '@angular/core';
import { ConfigurableFocusTrap, ConfigurableFocusTrapFactory } from '@angular/cdk/a11y';

/**
 * Shared keyboard and focus contract for portal dialogs and drawers.
 * Visual layout stays with the owning feature; modal semantics never do.
 */
@Directive({
  selector: '[portalDialog]',
  standalone: true,
  host: {
    role: 'dialog',
    'aria-modal': 'true',
    tabindex: '-1',
    '[attr.aria-labelledby]': 'portalDialogLabelledBy()',
    '[attr.aria-describedby]': 'portalDialogDescribedBy()'
  }
})
export class PortalDialog implements AfterViewInit, OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly focusTrapFactory = inject(ConfigurableFocusTrapFactory);
  private readonly previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  private focusTrap: ConfigurableFocusTrap | null = null;

  readonly portalDialogLabelledBy = input<string | null>(null);
  readonly portalDialogDescribedBy = input<string | null>(null);
  readonly portalDialogEscape = input(true);
  readonly portalDialogClose = output<void>();

  ngAfterViewInit(): void {
    this.focusTrap = this.focusTrapFactory.create(this.host.nativeElement);
    queueMicrotask(async () => {
      const focused = await this.focusTrap?.focusInitialElementWhenReady();
      if (!focused) {
        this.host.nativeElement.focus({ preventScroll: true });
      }
    });
  }

  @HostListener('document:keydown.escape', ['$event'])
  onEscape(rawEvent: Event): void {
    const event = rawEvent as KeyboardEvent;
    if (!this.portalDialogEscape() || !this.focusTrap) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    this.portalDialogClose.emit();
  }

  ngOnDestroy(): void {
    this.focusTrap?.destroy();
    this.focusTrap = null;
    queueMicrotask(() => this.previouslyFocused?.focus({ preventScroll: true }));
  }
}
