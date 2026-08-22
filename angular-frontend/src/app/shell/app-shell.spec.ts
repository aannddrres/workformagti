import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { TranslateService } from '@ngx-translate/core';
import { AppShell } from './app-shell';
import { AuthService } from '../core/auth/auth.service';
import { EffectiveAccess } from '../core/models/user';
import { UserProfileService } from '../core/auth/user-profile.service';
import { ThemeService } from '../core/theme/theme.service';
import { FontScaleService } from '../core/accessibility/font-scale.service';

describe('AppShell effective-access navigation', () => {
  function shellFor(role: string, access: EffectiveAccess): AppShell {
    TestBed.resetTestingModule();
    const profiles = {
      profile: () => null,
      ensureLoaded: () => of(null),
      ensureAccessLoaded: () => of(access),
      hasPermission: (permission: string) => access.bypass || access.permissions.includes(permission)
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { currentUser: () => ({ role, email: 'x@magti.ge' }) } },
        { provide: UserProfileService, useValue: profiles },
        { provide: TranslateService, useValue: { get: () => of('') } },
        { provide: ThemeService, useValue: {} },
        { provide: FontScaleService, useValue: {} },
        { provide: ActivatedRoute, useValue: { firstChild: null, snapshot: { data: {} } } }
      ]
    });
    return TestBed.runInInjectionContext(() => new AppShell());
  }

  function link(shell: AppShell, path: string): unknown {
    const sections = (shell as any).sections as Array<{ links: Array<{ path: string }> }>;
    return sections.flatMap((section) => section.links).find((candidate) => candidate.path === path);
  }

  function visible(shell: AppShell, path: string): boolean {
    return (shell as any).canSee(link(shell, path));
  }

  it('shows content navigation from the same permission source as the guard', () => {
    const shell = shellFor('operator', {
      role: 'operator', permissions: ['content.manage'], bypass: false
    });

    expect(visible(shell, '/admin/content')).toBe(true);
    expect(visible(shell, '/admin/categories')).toBe(true);
    expect(visible(shell, '/admin/overview')).toBe(false);
    expect(visible(shell, '/admin/access')).toBe(false);
  });

  it('hides content navigation after an effective deny', () => {
    const shell = shellFor('content_admin', {
      role: 'content_admin', permissions: ['articles.edit'], bypass: false
    });

    expect(visible(shell, '/admin/content')).toBe(false);
    expect(visible(shell, '/admin/categories')).toBe(false);
  });

  it('does not let content.manage alter manager or reading navigation eligibility', () => {
    const shell = shellFor('operator', {
      role: 'operator', permissions: ['content.manage'], bypass: false
    });

    expect(visible(shell, '/manager')).toBe(false);
    expect(visible(shell, '/reading')).toBe(true);
  });

  it('shows org navigation only to the system-admin role', () => {
    const operator = shellFor('operator', {
      role: 'operator', permissions: ['content.manage'], bypass: false
    });
    const admin = shellFor('admin', { role: 'admin', permissions: [], bypass: true });

    expect(visible(operator, '/admin/org')).toBe(false);
    expect(visible(operator, '/admin/org/assignments')).toBe(false);
    expect(visible(admin, '/admin/org')).toBe(true);
    expect(visible(admin, '/admin/org/assignments')).toBe(true);
  });
});
