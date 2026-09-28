import { vi } from 'vitest';
import { DOCUMENT, Injector } from '@angular/core';

import { LoginPage } from './login-page';

/**
 * Every way out of a session lands here, and the point of it is the page
 * load: routing to /login kept the last person's services -- their bookmarks,
 * profile and cached lists -- alive for whoever signed in next in the tab.
 */
describe('LoginPage', () => {
  function pageWith(assign: ReturnType<typeof vi.fn>): LoginPage {
    return Injector.create({
      providers: [{ provide: DOCUMENT, useValue: { location: { assign } } }, LoginPage]
    }).get(LoginPage);
  }

  it('loads the login screen afresh rather than routing to it', () => {
    const assign = vi.fn();

    pageWith(assign).open();

    expect(assign).toHaveBeenCalledWith('/login');
  });

  it('carries where the operator was and why they are back, encoded', () => {
    const assign = vi.fn();

    pageWith(assign).open({ returnUrl: '/articles/113?tab=quiz', reason: 'session-expired' });

    expect(assign).toHaveBeenCalledWith('/login?returnUrl=%2Farticles%2F113%3Ftab%3Dquiz&reason=session-expired');
  });
});
