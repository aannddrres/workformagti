import { DOCUMENT, Injector } from '@angular/core';
import { PortalXsrfTokenExtractor } from './portal-xsrf-token-extractor';

/**
 * The same bundle meets both cookie names: __Host-XSRF-TOKEN in production,
 * XSRF-TOKEN on the plain-HTTP stacks. Reading the wrong one sends no
 * X-XSRF-TOKEN, every POST is refused, and the refusal signs people out.
 */
describe('PortalXsrfTokenExtractor', () => {
  function withCookies(cookie: string): string | null {
    const injector = Injector.create({
      providers: [PortalXsrfTokenExtractor, { provide: DOCUMENT, useValue: { cookie } }]
    });
    return injector.get(PortalXsrfTokenExtractor).getToken();
  }

  it('reads the production cookie', () => {
    expect(withCookies('theme=dark; __Host-XSRF-TOKEN=prod-token_-')).toBe('prod-token_-');
  });

  it('reads the plain-HTTP cookie of the local and E2E stacks', () => {
    expect(withCookies('XSRF-TOKEN=local-token')).toBe('local-token');
  });

  it('prefers the prefixed cookie when both are present, as when a sibling host planted the other', () => {
    expect(withCookies('XSRF-TOKEN=planted; __Host-XSRF-TOKEN=genuine')).toBe('genuine');
    expect(withCookies('__Host-XSRF-TOKEN=genuine; XSRF-TOKEN=planted')).toBe('genuine');
  });

  it('answers null when there is no token yet', () => {
    expect(withCookies('')).toBeNull();
    expect(withCookies('other=1; XSRF-TOKEN-ish=nope')).toBeNull();
  });
});
