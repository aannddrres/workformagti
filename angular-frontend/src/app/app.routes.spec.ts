import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Route, Router, Routes } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of } from 'rxjs';
import { routes } from './app.routes';
import { AuthService } from './core/auth/auth.service';
import { EffectiveAccess } from './core/models/user';
import { UserProfileService } from './core/auth/user-profile.service';

@Component({ standalone: true, template: '' })
class StubPage {}

describe('effective-access route boundaries', () => {
  function child(path: string): Route {
    const shell = routes.find((route) => route.path === '');
    const found = shell?.children?.find((route) => route.path === path);
    if (!found) {
      throw new Error(`Route not found: ${path}`);
    }
    return found;
  }

  function adminChild(path: string): Route {
    const found = child('admin').children?.find((route) => route.path === path);
    if (!found) {
      throw new Error(`Admin route not found: ${path}`);
    }
    return found;
  }

  async function harnessFor(
    role: string,
    access: EffectiveAccess | null
  ): Promise<RouterTestingHarness> {
    TestBed.resetTestingModule();
    const testRoutes: Routes = [
      { path: '', component: StubPage },
      {
        path: 'admin',
        canActivate: child('admin').canActivate,
        children: [
          { path: 'content', component: StubPage },
          { path: 'trash', component: StubPage },
          { path: 'categories', component: StubPage },
          { path: 'overview', component: StubPage, canActivate: adminChild('overview').canActivate },
          { path: 'access', component: StubPage, canActivate: adminChild('access').canActivate },
          { path: 'org', component: StubPage, canActivate: adminChild('org').canActivate },
          {
            path: 'org/assignments',
            component: StubPage,
            canActivate: adminChild('org/assignments').canActivate
          },
          { path: 'org/backfill', component: StubPage, canActivate: adminChild('org/backfill').canActivate }
        ]
      },
      { path: 'admin/broadcasts', component: StubPage, canActivate: child('admin/broadcasts').canActivate },
      { path: 'admin/exports', component: StubPage, canActivate: child('admin/exports').canActivate },
      { path: 'manager', component: StubPage, canActivate: child('manager').canActivate },
      { path: 'reading', component: StubPage, canActivate: child('reading').canActivate },
      { path: '**', redirectTo: '' }
    ];
    TestBed.configureTestingModule({
      providers: [
        provideRouter(testRoutes),
        { provide: AuthService, useValue: { currentUser: () => ({ role }) } },
        {
          provide: UserProfileService,
          useValue: {
            ensureAccessLoaded: () => of(access),
            hasPermission: (permission: string) =>
              access?.bypass === true || access?.permissions.includes(permission) === true,
            canPublishAnnouncement: () => access?.can_publish_announcement === true
          }
        }
      ]
    });
    return RouterTestingHarness.create();
  }

  function currentUrl(): string {
    return TestBed.inject(Router).url;
  }

  it('opens admin content for an operator with content.manage', async () => {
    const harness = await harnessFor('operator', {
      role: 'operator', permissions: ['content.manage'], bypass: false, can_publish_announcement: true
    });

    await harness.navigateByUrl('/admin/content');

    expect(currentUrl()).toBe('/admin/content');

    await harness.navigateByUrl('/admin/trash');
    expect(currentUrl()).toBe('/admin/trash');
  });

  it('opens admin content and overview for the explicit system-admin bypass', async () => {
    const harness = await harnessFor('admin', { role: 'admin', permissions: [], bypass: true, can_publish_announcement: true });

    await harness.navigateByUrl('/admin/content');
    expect(currentUrl()).toBe('/admin/content');

    await harness.navigateByUrl('/admin/overview');
    expect(currentUrl()).toBe('/admin/overview');
  });

  it('closes admin content for an operator without the effective permission', async () => {
    const harness = await harnessFor('operator', { role: 'operator', permissions: [], bypass: false, can_publish_announcement: false });

    await harness.navigateByUrl('/admin/content');

    expect(currentUrl()).toBe('/');

    await harness.navigateByUrl('/admin/trash');
    expect(currentUrl()).toBe('/');
  });

  it('closes admin content for a content admin with an effective deny', async () => {
    const harness = await harnessFor('content_admin', {
      role: 'content_admin', permissions: ['articles.edit'], bypass: false, can_publish_announcement: false
    });

    await harness.navigateByUrl('/admin/content');

    expect(currentUrl()).toBe('/');
  });

  it('fails closed when effective access cannot be loaded', async () => {
    const harness = await harnessFor('operator', null);

    await harness.navigateByUrl('/admin/content');

    expect(currentUrl()).toBe('/');
  });

  it('keeps overview and access outside content.manage', async () => {
    const harness = await harnessFor('operator', {
      role: 'operator', permissions: ['content.manage'], bypass: false, can_publish_announcement: true
    });

    await harness.navigateByUrl('/admin/overview');
    expect(currentUrl()).toBe('/admin/content');

    await harness.navigateByUrl('/admin/access');
    expect(currentUrl()).toBe('/');
  });

  it('keeps every org administration screen system-admin-only', async () => {
    const operator = await harnessFor('operator', {
      role: 'operator', permissions: ['content.manage'], bypass: false, can_publish_announcement: true
    });

    for (const url of ['/admin/org', '/admin/org/assignments', '/admin/org/backfill']) {
      await operator.navigateByUrl(url);
      expect(currentUrl()).toBe('/');
    }

    const admin = await harnessFor('admin', { role: 'admin', permissions: [], bypass: true, can_publish_announcement: true });
    for (const url of ['/admin/org', '/admin/org/assignments', '/admin/org/backfill']) {
      await admin.navigateByUrl(url);
      expect(currentUrl()).toBe(url);
    }
  });

  it('keeps the sensitive export center system-admin-only', async () => {
    const operator = await harnessFor('operator', {
      role: 'operator', permissions: ['content.manage', 'reports.export'], bypass: false, can_publish_announcement: true
    });
    await operator.navigateByUrl('/admin/exports');
    expect(currentUrl()).toBe('/');

    const admin = await harnessFor('admin', {
      role: 'admin', permissions: [], bypass: true, can_publish_announcement: true
    });
    await admin.navigateByUrl('/admin/exports');
    expect(currentUrl()).toBe('/admin/exports');
  });

  it('keeps Phase 4 and Phase 5 role gates unchanged', async () => {
    const harness = await harnessFor('operator', {
      role: 'operator', permissions: ['content.manage'], bypass: false, can_publish_announcement: true
    });

    await harness.navigateByUrl('/manager');
    expect(currentUrl()).toBe('/');

    await harness.navigateByUrl('/reading');
    expect(currentUrl()).toBe('/reading');
  });

  it('opens broadcasts from the composite backend capability without widening other admin routes', async () => {
    const leader = await harnessFor('operator', {
      role: 'operator', permissions: [], bypass: false, can_publish_announcement: true
    });
    await leader.navigateByUrl('/admin/broadcasts');
    expect(currentUrl()).toBe('/admin/broadcasts');
    await leader.navigateByUrl('/admin/content');
    expect(currentUrl()).toBe('/');

    const employee = await harnessFor('operator', {
      role: 'operator', permissions: [], bypass: false, can_publish_announcement: false
    });
    await employee.navigateByUrl('/admin/broadcasts');
    expect(currentUrl()).toBe('/');
  });

  it('does not expose a private-messaging profile route', () => {
    expect(child('profile').children?.some((route) => route.path === 'messages')).toBe(false);
  });
});
