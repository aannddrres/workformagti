import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { TitleStrategy, provideRouter, withInMemoryScrolling } from '@angular/router';
import { HttpXsrfTokenExtractor, provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';

import { routes } from './app.routes';
import { PortalTitleStrategy } from './shell/portal-title-strategy';
import { apiBaseUrlInterceptor } from './core/http/api-base-url.interceptor';
import { unauthorizedInterceptor } from './core/http/unauthorized.interceptor';
import { PortalXsrfTokenExtractor } from './core/http/portal-xsrf-token-extractor';

// Georgian is the primary and only reviewed language today (see docs/i18n-catalog/).
// fallbackLang stays 'ka' so an unreviewed/missing 'en' key falls back to the
// real Georgian text instead of showing a raw translation key on screen.
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withInMemoryScrolling({ scrollPositionRestoration: 'top', anchorScrolling: 'enabled' })),
    { provide: TitleStrategy, useClass: PortalTitleStrategy },
    provideHttpClient(withInterceptors([apiBaseUrlInterceptor, unauthorizedInterceptor])),
    // The CSRF cookie is __Host-XSRF-TOKEN in production, XSRF-TOKEN on plain HTTP.
    { provide: HttpXsrfTokenExtractor, useClass: PortalXsrfTokenExtractor },
    provideTranslateService({ lang: 'ka', fallbackLang: 'ka' }),
    provideTranslateHttpLoader({ prefix: '/i18n/', suffix: '.json' })
  ]
};
