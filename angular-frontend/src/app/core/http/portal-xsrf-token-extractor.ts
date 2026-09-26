import { DOCUMENT, Injectable, inject } from '@angular/core';
import { HttpXsrfTokenExtractor } from '@angular/common/http';

/**
 * Which cookie holds the CSRF token depends on the backend's COOKIE_SECURE:
 * __Host-XSRF-TOKEN where cookies are Secure (production, which requires
 * it), XSRF-TOKEN on plain HTTP (the local and E2E stacks) --
 * PortalProperties.Cookie#csrfCookieName. Angular's own extractor reads one
 * fixed name, and the same production bundle runs on both, so this reads
 * whichever is present and prefers the prefixed one, which no sibling host
 * under the same parent domain can plant.
 *
 * The token is base64url, so the cookie value is used as it is.
 */
@Injectable()
export class PortalXsrfTokenExtractor implements HttpXsrfTokenExtractor {
  private readonly document = inject(DOCUMENT);

  getToken(): string | null {
    let plain: string | null = null;
    for (const pair of (this.document.cookie || '').split(';')) {
      const separator = pair.indexOf('=');
      if (separator < 0) {
        continue;
      }
      const name = pair.slice(0, separator).trim();
      const value = pair.slice(separator + 1).trim();
      if (name === '__Host-XSRF-TOKEN') {
        return value;
      }
      if (name === 'XSRF-TOKEN') {
        plain = value;
      }
    }
    return plain;
  }
}
