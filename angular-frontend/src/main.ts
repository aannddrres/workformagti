import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

// Applied before bootstrap, synchronously, so the correct theme is present
// on first paint (no flash of the wrong theme). ThemeService reads this
// same class back at construction rather than re-deciding it.
if (localStorage.getItem('magti_dark_mode') === 'true') {
  document.documentElement.classList.add('dark');
}

bootstrapApplication(App, appConfig)
  .catch((err) => console.error(err));
