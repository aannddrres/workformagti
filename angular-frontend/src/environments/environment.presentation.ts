/**
 * Presentation build (`ng build --configuration presentation`, wired via
 * angular.json's fileReplacements and selected by the BUILD_CONFIGURATION
 * build arg in angular-frontend/Dockerfile).
 *
 * Identical to the production build in every respect except the inactivity
 * timer. The 30-minute shared-workstation sign-out is correct for a call
 * centre and wrong for a demo: the activity events that reset it are
 * pointerdown/keydown/wheel/touchstart -- deliberately NOT mousemove -- so
 * standing in front of one dashboard and talking about it reads as
 * inactivity. At minute 28 a warning modal appears over the screen and at
 * minute 30 the portal signs itself out and returns to /login. Observed
 * live on the presentation stack, mid-review, on 2026-09-01.
 *
 * Raising this alone is not enough: the backend expires the session on its
 * own `portal.security.session.idle-minutes` clock and the frontend stops
 * sending heartbeats once the user stops interacting, so
 * docker-compose.presentation.yml raises the backend to match. Both are
 * scoped to the loopback-only demo stack; production keeps 30.
 */
export const environment = {
  production: true,
  apiBaseUrl: '',
  idleLimitMinutes: 480
};
