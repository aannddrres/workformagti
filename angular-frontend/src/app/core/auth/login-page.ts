import { DOCUMENT, Injectable, inject } from '@angular/core';

/**
 * Leaves for the login screen by loading it afresh, never by routing to it.
 *
 * Every root service -- bookmarks, the profile, effective access, whatever a
 * page cached -- lives as long as the tab. Routing to /login kept all of it,
 * so on a shared call-centre PC the next person to sign in in the same tab
 * inherited the previous one's data until something happened to reload it:
 * their bookmarks showed as the newcomer's (e2e/operator-browsing.spec.ts,
 * "the next person on the same browser sees nothing of the last"). A page
 * load starts every service empty. ASVS V14.3.1.
 *
 * Used by every way out: the sign-out button, the idle timeout and a session
 * the server ended (unauthorized.interceptor.ts).
 */
@Injectable({ providedIn: 'root' })
export class LoginPage {
  private readonly document = inject(DOCUMENT);

  open(queryParams: Record<string, string> = {}): void {
    const query = new URLSearchParams(queryParams).toString();
    // A fixed path of this app, whatever the parameters say (open-redirect
    // tripwire, tests/test_asvs_tripwires.py).
    this.document.location.assign('/login' + (query ? `?${query}` : ''));
  }
}
