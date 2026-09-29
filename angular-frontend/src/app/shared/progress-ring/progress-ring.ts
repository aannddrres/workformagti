import { Component, computed, input } from '@angular/core';
import { CompletionTier, completionTier } from '../completion-tier';

const RING: Record<CompletionTier, string> = {
  good: '#10b981',
  middle: '#f59e0b',
  low: '#dc2626',
  none: '#94a3b8'
};

/**
 * Pure presentational SVG ring, port of fetchAndRenderMyProgress's ring
 * math (app-renderers.js:895-945): radius 28.
 * The original animates the fill with a requestAnimationFrame count-up;
 * here a CSS transition on stroke-dashoffset gives the same "fills in"
 * feel without hand-rolling a JS animation loop -- a deliberate simplification.
 */
@Component({
  selector: 'app-progress-ring',
  standalone: true,
  templateUrl: './progress-ring.html'
})
export class ProgressRing {
  readonly percentage = input.required<number>();

  protected readonly radius = 28;
  protected readonly circumference = 2 * Math.PI * this.radius;
  protected readonly offset = computed(() => this.circumference * (1 - this.percentage() / 100));
  /**
   * The ring kept the original 80/40 thresholds and painted its low band in
   * the brand red, while the team screen beside it used 80/30. It now takes
   * the one scale from shared/completion-tier.ts (owner decision კ13), and
   * the low band is the status red, not the colour of the primary button.
   * Bound with [style.stroke], as before.
   */
  protected readonly color = computed(() => RING[completionTier(this.percentage())]);
}
