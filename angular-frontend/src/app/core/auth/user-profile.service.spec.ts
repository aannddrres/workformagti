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

/**
 * An administrator's change to someone's rights reached that person's menu
 * only on a reload; the server was already enforcing it (checked with two
 * browsers on 2026-09-30). refreshAccess() is how the screen catches up.
 */
describe('UserProfileService refreshAccess', () => {
  const operator = { role: 'operator', permissions: [] as string[], bypass: false, can_publish_announcement: false };
  const granted = { ...operator, permissions: ['content.manage'] };

  function serviceWith(effectiveAccess: ReturnType<typeof vi.fn>, signedIn = true) {
    TestBed.resetTestingModule();
    const currentUser = signal(signedIn ? { email: 'operator@magti.ge', role: 'operator' } : null);
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: { currentUser } },
        { provide: UsersService, useValue: { me: () => of(null), effectiveAccess } }
      ]
    });
    return { service: TestBed.inject(UserProfileService), currentUser };
  }

  it('replaces the cached access, so the menu follows a change made after sign-in', async () => {
    const fetch = vi.fn().mockReturnValueOnce(of(operator)).mockReturnValueOnce(of(granted));
    const { service } = serviceWith(fetch);
    await firstValueFrom(service.ensureAccessLoaded());
    expect(service.hasPermission('content.manage')).toBe(false);

    service.refreshAccess();

    expect(fetch).toHaveBeenCalledTimes(2);
    expect(service.hasPermission('content.manage')).toBe(true);
  });

  it('asks at most once per interval, however many refusals arrive', async () => {
    const fetch = vi.fn(() => of(operator));
    const { service } = serviceWith(fetch);
    await firstValueFrom(service.ensureAccessLoaded());

    service.refreshAccess(5_000);
    service.refreshAccess(5_000);
    service.refreshAccess(5_000);

    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('does nothing before the first load, leaving that to the guards', () => {
    const fetch = vi.fn(() => of(operator));
    const { service } = serviceWith(fetch);

    service.refreshAccess();

    expect(fetch).not.toHaveBeenCalled();
  });

  it('keeps the cached access when the re-read fails; the server still decides', async () => {
    const fetch = vi.fn().mockReturnValueOnce(of(granted)).mockReturnValueOnce(throwError(() => new Error('offline')));
    const { service } = serviceWith(fetch);
    await firstValueFrom(service.ensureAccessLoaded());

    service.refreshAccess();

    expect(service.hasPermission('content.manage')).toBe(true);
  });

  it('re-reads when the person comes back to the tab', async () => {
    const fetch = vi.fn().mockReturnValueOnce(of(operator)).mockReturnValueOnce(of(granted));
    const { service } = serviceWith(fetch);
    await firstValueFrom(service.ensureAccessLoaded());

    document.dispatchEvent(new Event('visibilitychange'));

    expect(fetch).toHaveBeenCalledTimes(2);
    expect(service.hasPermission('content.manage')).toBe(true);
  });
});
