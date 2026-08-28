import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';
import { firstValueFrom, Observable, of } from 'rxjs';
import { EffectiveAccess } from '../models/user';
import { adminOverviewGuard } from './role.guard';
import { UserProfileService } from './user-profile.service';

/**
 * `/admin/overview` became system-admin-only in the Phase 0 access fix, but
 * `/admin` and the legacy `/admin/main` still redirect there. With a plain
 * refusing guard that chain sent a content admin to `/` -- out of the admin
 * area entirely, rather than to the content workspace they do have.
 */
describe('adminOverviewGuard', () => {
  async function run(access: EffectiveAccess | null): Promise<boolean | UrlTree> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: UserProfileService,
          useValue: {
            ensureAccessLoaded: () => of(access),
            hasPermission: (permission: string) =>
              access?.bypass === true || access?.permissions.includes(permission) === true
          }
        }
      ]
    });
    const result = TestBed.runInInjectionContext(
      () => adminOverviewGuard({} as never, {} as never)
    ) as Observable<boolean | UrlTree>;
    return firstValueFrom(result);
  }

  function target(result: boolean | UrlTree): string {
    const router = TestBed.inject(Router);
    return router.serializeUrl(result as UrlTree);
  }

  it('lets the system-admin bypass through', async () => {
    expect(await run({ role: 'admin', permissions: [], bypass: true, can_publish_announcement: true })).toBe(true);
  });

  it('sends any content manager to the content workspace, not to the overview', async () => {
    const result = await run({ role: 'operator', permissions: ['content.manage'], bypass: false, can_publish_announcement: true });

    expect(result).not.toBe(true);
    expect(target(result)).toBe('/admin/content');
  });

  it('opens the overview only from the independent stats.view capability', async () => {
    expect(await run({
      role: 'operator', permissions: ['stats.view'], bypass: false, can_publish_announcement: false
    })).toBe(true);

    expect(await run({
      role: 'content_admin', permissions: ['content.manage', 'stats.view'], bypass: false,
      can_publish_announcement: true
    })).toBe(true);
  });

  it('fails closed when effective access cannot be loaded', async () => {
    expect(target(await run(null))).toBe('/forbidden');
  });
});
