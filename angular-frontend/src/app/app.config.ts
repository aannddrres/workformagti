import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withInMemoryScrolling } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { apiBaseUrlInterceptor } from './core/http/api-base-url.interceptor';
import { unauthorizedInterceptor } from './core/http/unauthorized.interceptor';

// Georgian is the primary and only reviewed language today (see docs/i18n-catalog/).
// fallbackLang stays 'ka' so an unreviewed/missing 'en' key falls back to the
// real Georgian text instead of showing a raw translation key on screen.
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withInMemoryScrolling({ scrollPositionRestoration: 'top', anchorScrolling: 'enabled' })),
    provideHttpClient(withInterceptors([apiBaseUrlInterceptor, authInterceptor, unauthorizedInterceptor])),
    provideTranslateService({ lang: 'ka', fallbackLang: 'ka' }),
    provideTranslateHttpLoader({ prefix: '/i18n/', suffix: '.json' })
  ]
};
