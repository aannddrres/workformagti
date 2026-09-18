/**
 * Production build (`ng build --configuration production`, wired via
 * angular.json's fileReplacements). apiBaseUrl defaults to empty (same
 * behavior as dev) since the actual Kubernetes ingress topology -- whether
 * Angular and the Java API share one origin/host or are split across two --
 * isn't decided yet (see docs/QUESTIONS_FOR_IT.md #4). If they end up split,
 * set this to the API's real origin (e.g. "https://portal-api.magti.ge")
 * before deploying; apiBaseUrlInterceptor already prepends it to every
 * /api and /uploads request, no service code needs to change.
 */
export const environment = {
  production: true,
  apiBaseUrl: '',
  /** See environment.ts. Production keeps the 30-minute shared-workstation timer. */
  idleLimitMinutes: 30
};
