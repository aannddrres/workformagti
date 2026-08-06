import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';

/** Attaches the Bearer token to every same-origin API request. The backend's
 *  JwtAuthenticationFilter accepts either this header or the httpOnly
 *  cookie (header checked first) -- see JwtAuthenticationFilter.java. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const token = inject(AuthService).getToken();
  if (!token) {
    return next(req);
  }
  return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};
