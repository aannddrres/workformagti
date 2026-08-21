import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';
import { AuthService } from './auth.service';
import { adminOverviewGuard } from './role.guard';

/**
 * `/admin/overview` became system-admin-only in the Phase 0 access fix, but
 * `/admin` and the legacy `/admin/main` still redirect there. With a plain
 * refusing guard that chain sent a content admin to `/` -- out of the admin
 * area entirely, rather than to the content workspace they do have.
 */
describe('adminOverviewGuard', () => {
  function run(user: { role: string } | null): boolean | UrlTree {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { currentUser: () => user } }
      ]
    });
    return TestBed.runInInjectionContext(() => adminOverviewGuard({} as never, {} as never)) as
      | boolean
      | UrlTree;
  }

  function target(result: boolean | UrlTree): string {
    const router = TestBed.inject(Router);
    return router.serializeUrl(result as UrlTree);
  }

  it('lets a system admin through', () => {
    expect(run({ role: 'admin' })).toBe(true);
  });

  it('sends a content admin to the content workspace, not to the dashboard', () => {
    const result = run({ role: 'content_admin' });

    expect(result).not.toBe(true);
    expect(target(result)).toBe('/admin/content');
  });

  it('sends anyone else home', () => {
    expect(target(run({ role: 'operator' }))).toBe('/');
    expect(target(run({ role: 'manager' }))).toBe('/');
  });

  it('sends an unauthenticated caller to login', () => {
    expect(target(run(null))).toBe('/login');
  });
});
