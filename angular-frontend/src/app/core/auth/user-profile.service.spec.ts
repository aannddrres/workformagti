import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of, throwError } from 'rxjs';
import { UsersService } from '../services/users.service';
import { AuthService } from './auth.service';
import { UserProfileService } from './user-profile.service';

describe('UserProfileService effective access', () => {
  function serviceWith(effectiveAccess: ReturnType<typeof vi.fn>): UserProfileService {
    TestBed.resetTestingModule();
    const currentUser = signal({ email: 'operator@magti.ge', role: 'operator' });
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: { currentUser } },
        {
          provide: UsersService,
          useValue: { me: () => of(null), effectiveAccess }
        }
      ]
    });
    return TestBed.inject(UserProfileService);
  }

  it('shares one effective-access fetch between guard and navigation consumers', async () => {
    const fetch = vi.fn(() => of({
      role: 'operator', permissions: ['content.manage'], bypass: false, can_publish_announcement: true
    }));
    const service = serviceWith(fetch);

    await Promise.all([
      firstValueFrom(service.ensureAccessLoaded()),
      firstValueFrom(service.ensureAccessLoaded())
    ]);

    expect(fetch).toHaveBeenCalledTimes(1);
    expect(service.hasPermission('content.manage')).toBe(true);
    expect(service.hasPermission('system.audit')).toBe(false);
    expect(service.canPublishAnnouncement()).toBe(true);
  });

  it('fails closed when the effective-access fetch fails', async () => {
    const service = serviceWith(vi.fn(() => throwError(() => new Error('offline'))));

    expect(await firstValueFrom(service.ensureAccessLoaded())).toBeNull();
    expect(service.hasPermission('content.manage')).toBe(false);
    expect(service.canPublishAnnouncement()).toBe(false);
  });
});
