/**
 * Dev build (default, `ng serve`/`ng build` without `--configuration production`).
 * apiBaseUrl empty means every request stays same-origin-relative (`/api/...`),
 * proxied to localhost:8080 by proxy.conf.json -- unchanged from today's
 * behavior.
 */
export const environment = {
  production: false,
  apiBaseUrl: ''
};
