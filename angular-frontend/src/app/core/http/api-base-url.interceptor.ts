import { HttpInterceptorFn } from '@angular/common/http';
import { environment } from '../../../environments/environment';

/**
 * Prepends environment.apiBaseUrl to same-origin-relative /api and /uploads
 * requests. Every service in this app calls these paths as plain relative
 * strings (e.g. `this.http.get('/api/articles')`); apiBaseUrl is empty by
 * default (dev and prod alike), so this is a no-op today. It exists so a
 * split-origin production deployment (Angular and the Java API on different
 * hosts) only needs environment.prod.ts's one value changed, not every
 * service file. See environment.prod.ts's comment.
 */
export const apiBaseUrlInterceptor: HttpInterceptorFn = (req, next) => {
  if (!environment.apiBaseUrl || (!req.url.startsWith('/api') && !req.url.startsWith('/uploads'))) {
    return next(req);
  }
  return next(req.clone({ url: environment.apiBaseUrl + req.url }));
};
