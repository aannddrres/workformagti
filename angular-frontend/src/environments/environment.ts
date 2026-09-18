/**
 * Dev build (default, `ng serve`/`ng build` without `--configuration production`).
 * apiBaseUrl empty means every request stays same-origin-relative (`/api/...`),
 * proxied to localhost:8080 by proxy.conf.json -- unchanged from today's
 * behavior.
 */
export const environment = {
  production: false,
  apiBaseUrl: '',
  /**
   * Minutes of inactivity before the portal signs itself out. Matches the
   * backend's `portal.security.session.idle-minutes` default; raise BOTH or
   * neither, since the backend expires the session on its own clock and the
   * frontend only stops sending heartbeats once the user stops interacting.
   *
   * Overridden by the `presentation` build configuration -- a demo where
   * someone talks over one screen for half an hour is exactly the case this
   * shared-workstation timer is meant to punish, and it did.
   */
  idleLimitMinutes: 30
};
