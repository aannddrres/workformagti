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
  protected readonly color = computed(() => {
    const p = this.percentage();
    if (p >= 80) return '#10b981';
    if (p >= 40) return '#f59e0b';
    return '#E30613';
  });
}
