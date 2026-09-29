import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AppShell } from './app-shell';
import { AuthService } from '../core/auth/auth.service';
import { EffectiveAccess } from '../core/models/user';
import { UserProfileService } from '../core/auth/user-profile.service';
import { ThemeService } from '../core/theme/theme.service';
import { FontScaleService } from '../core/accessibility/font-scale.service';

describe('AppShell effective-access navigation', () => {
  function shellFor(role: string, access: EffectiveAccess, scale = 1): AppShell {
    TestBed.resetTestingModule();
    const profiles = {
      profile: () => null,
      ensureLoaded: () => of(null),
      ensureAccessLoaded: () => of(access),
      hasPermission: (permission: string) => access.bypass || access.permissions.includes(permission),
      canPublishAnnouncement: () => access.can_publish_announcement
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { currentUser: () => ({ role, email: 'x@magti.ge' }) } },
        { provide: UserProfileService, useValue: profiles },
        { provide: ThemeService, useValue: {} },
        { provide: FontScaleService, useValue: { scale: () => scale } }
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
      role: 'operator', permissions: ['content.manage'], bypass: false, can_publish_announcement: true
    });

    expect(visible(shell, '/admin/content')).toBe(true);
    expect(visible(shell, '/admin/trash')).toBe(true);
    expect(visible(shell, '/admin/categories')).toBe(true);
    expect(visible(shell, '/admin/overview')).toBe(false);
    expect(visible(shell, '/admin/access')).toBe(false);
  });

  it('shows aggregate statistics from stats.view without showing content administration', () => {
    const shell = shellFor('operator', {
      role: 'operator', permissions: ['stats.view'], bypass: false, can_publish_announcement: false
    });

    expect(visible(shell, '/admin/overview')).toBe(true);
    expect(visible(shell, '/admin/content')).toBe(false);
    expect(visible(shell, '/admin/access')).toBe(false);
  });

  it('hides content navigation after an effective deny', () => {
    const shell = shellFor('content_admin', {
      role: 'content_admin', permissions: ['articles.edit'], bypass: false, can_publish_announcement: false
    });

    expect(visible(shell, '/admin/content')).toBe(false);
    expect(visible(shell, '/admin/trash')).toBe(false);
    expect(visible(shell, '/admin/categories')).toBe(false);
  });

  it('does not let content.manage alter manager or reading navigation eligibility', () => {
    const shell = shellFor('operator', {
      role: 'operator', permissions: ['content.manage'], bypass: false, can_publish_announcement: true
    });

    expect(visible(shell, '/manager')).toBe(false);
    expect(visible(shell, '/reading')).toBe(true);
  });

  it('shows org navigation only to the system-admin role', () => {
    const operator = shellFor('operator', {
      role: 'operator', permissions: ['content.manage'], bypass: false, can_publish_announcement: true
    });
    const admin = shellFor('admin', { role: 'admin', permissions: [], bypass: true, can_publish_announcement: true });

    expect(visible(operator, '/admin/org')).toBe(false);
    expect(visible(operator, '/admin/org/assignments')).toBe(false);
    expect(visible(admin, '/admin/org')).toBe(true);
    expect(visible(admin, '/admin/org/assignments')).toBe(true);
    expect(visible(admin, '/admin/overview')).toBe(true);
    expect(visible(operator, '/admin/exports')).toBe(false);
    expect(visible(admin, '/admin/exports')).toBe(true);
  });

  it('shows broadcasts to a group leader capability without showing content administration', () => {
    const leader = shellFor('operator', {
      role: 'operator', permissions: [], bypass: false, can_publish_announcement: true
    });
    expect(visible(leader, '/admin/broadcasts')).toBe(true);
    expect(visible(leader, '/admin/content')).toBe(false);

    const employee = shellFor('operator', {
      role: 'operator', permissions: [], bypass: false, can_publish_announcement: false
    });
    expect(visible(employee, '/admin/broadcasts')).toBe(false);
  });

  // routerLinkActive marked every prefix, so the leaders page lit the
  // structure entry as well as its own.
  it('marks only the deepest menu entry the current page sits under', () => {
    const admin = shellFor('admin', { role: 'admin', permissions: [], bypass: true, can_publish_announcement: true });
    const active = (url: string, path: string): boolean => {
      (admin as any).currentPath.set(url);
      return (admin as any).isActive(link(admin, path));
    };

    expect(active('/admin/org/assignments', '/admin/org/assignments')).toBe(true);
    expect(active('/admin/org/assignments', '/admin/org')).toBe(false);
    expect(active('/admin/org', '/admin/org')).toBe(true);
    expect(active('/admin/org/backfill', '/admin/org')).toBe(true);
    expect(active('/news/12', '/news')).toBe(true);
    expect(active('/news', '/')).toBe(false);
    expect(active('/', '/')).toBe(true);
    expect(active('/newsletter', '/news')).toBe(false);
  });

  // The landing guard sends these roles past the home page, so a "მთავარი"
  // entry led them to a screen they never saw (owner decision კ12).
  it('leads each workspace role with its own section and drops home for it', () => {
    const firstSection = (shell: AppShell): string => (shell as any).orderedSections()[0].key;
    const manager = shellFor('manager', { role: 'manager', permissions: [], bypass: false, can_publish_announcement: true });
    const content = shellFor('content_admin', {
      role: 'content_admin', permissions: ['content.manage'], bypass: false, can_publish_announcement: false
    });
    const admin = shellFor('admin', { role: 'admin', permissions: [], bypass: true, can_publish_announcement: true });
    const operator = shellFor('operator', { role: 'operator', permissions: [], bypass: false, can_publish_announcement: false });

    expect(firstSection(manager)).toBe('team');
    expect(firstSection(content)).toBe('content');
    expect(firstSection(admin)).toBe('control');
    expect(firstSection(operator)).toBe('work');
    expect(visible(manager, '/')).toBe(false);
    expect(visible(content, '/')).toBe(false);
    expect(visible(admin, '/')).toBe(false);
    expect(visible(operator, '/')).toBe(true);
  });

  it('folds the menu to icons from 150% text, and lets the button open it', () => {
    const access: EffectiveAccess = { role: 'operator', permissions: [], bypass: false, can_publish_announcement: false };
    expect((shellFor('operator', access, 1.3) as any).sidebarCollapsed()).toBe(false);

    const large = shellFor('operator', access, 1.5) as any;
    expect(large.sidebarCollapsed()).toBe(true);
    large.toggleSidebar();
    expect(large.sidebarCollapsed()).toBe(false);
  });
});
