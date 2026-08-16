import { Component, computed, input } from '@angular/core';

/**
 * Pure presentational SVG ring, port of fetchAndRenderMyProgress's ring
 * math (app-renderers.js:895-945): radius 28, color thresholds at 80%/40%.
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
   * Bound with [style.stroke] rather than [attr.stroke]: a CSS custom property
   * is only resolved in a style property, never in an SVG presentation
   * attribute, and the low band now points at the shared brand token instead
   * of holding its own copy of the hex.
   *
   * The 80/40 thresholds and the green/amber values are the original ones
   * (app-renderers.js:895-945) and are deliberately left as literals: they are
   * a status scale, not brand, and unifying the two is a design decision this
   * refactor does not make on its own. See styles.css on splitting brand from
   * status as the follow-up.
   */
  protected readonly color = computed(() => {
    const p = this.percentage();
    if (p >= 80) return '#10b981';
    if (p >= 40) return '#f59e0b';
    return 'rgb(var(--brand-600))';
  });
}
