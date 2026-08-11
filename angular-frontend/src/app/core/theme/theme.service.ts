import { Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'magti_dark_mode';

/**
 * Port of app-core.js's toggleHeaderTheme/applyStoredSettings dark-mode
 * slice (app-core.js:4016-4080) -- same localStorage key, same manual-only
 * behavior (no `prefers-color-scheme` auto-detection; Python doesn't do
 * that either, so neither does this). The `dark` class this toggles is
 * read by every `dark:` Tailwind class already written across the Angular
 * app (`darkMode: 'class'` in tailwind.config.js) -- until this service
 * existed, nothing ever set that class, so dark mode never visually
 * activated despite the classes being present everywhere.
 *
 * <p>The initial class application happens synchronously in main.ts,
 * before bootstrap, to avoid a flash of the wrong theme; this service's
 * constructor just reads back whatever main.ts already applied to
 * `<html>` rather than re-deciding it, so the two can't disagree.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly isDark = signal(document.documentElement.classList.contains('dark'));

  toggle(): void {
    const next = !this.isDark();
    document.documentElement.classList.toggle('dark', next);
    localStorage.setItem(STORAGE_KEY, next ? 'true' : 'false');
    this.isDark.set(next);
  }
}
