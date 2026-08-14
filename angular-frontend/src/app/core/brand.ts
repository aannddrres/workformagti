/**
 * Reads the brand colour out of the CSS custom property defined in
 * src/styles.css, for the two places that cannot use a Tailwind class.
 *
 * Everything that renders as DOM should use the `brand` utilities
 * (`bg-brand`, `text-brand`, `focus:ring-brand`, `bg-brand/10`) instead of
 * calling this. This exists only for canvas: Chart.js paints into a bitmap,
 * where `rgb(var(--brand-600))` is never resolved by the browser, so the
 * value has to be computed first.
 *
 * Going through the same custom property keeps canvas in step with the rest of
 * the UI — before this, the charts held their own copies of the brand hex
 * (and one hand-expanded `rgba(185, 28, 28, 0.12)`), so re-branding would have
 * silently left the charts on the old colour.
 */
const FALLBACK_CHANNELS = '185 28 28';

/**
 * @param alpha 0–1 opacity; omitted means fully opaque.
 * @returns e.g. `rgb(185 28 28)` or `rgb(185 28 28 / 0.12)`.
 */
export function brandRgb(alpha = 1): string {
  const channels = readChannels('--brand-600');
  return alpha === 1 ? `rgb(${channels})` : `rgb(${channels} / ${alpha})`;
}

function readChannels(property: string): string {
  // Guard for SSR / unit tests, where there is no document to measure.
  if (typeof document === 'undefined') {
    return FALLBACK_CHANNELS;
  }
  const value = getComputedStyle(document.documentElement).getPropertyValue(property).trim();
  return value || FALLBACK_CHANNELS;
}
