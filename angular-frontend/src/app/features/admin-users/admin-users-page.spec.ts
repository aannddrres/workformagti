import { vi } from 'vitest';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';
import { AdminUsersPage } from './admin-users-page';
import { AdminUsersService } from '../../core/services/admin-users.service';
import { ToastService } from '../../core/notifications/toast.service';
import { ConfirmService } from '../../core/notifications/confirm.service';
import { AuthService } from '../../core/auth/auth.service';

/**
 * FE-10 and FE-04. The audit's point about this app's tests was not that
 * there were few, but that the ones that existed only covered happy paths --
 * "nearly every finding in audit 3 is one a test like that would have
 * caught". These cover the error branches of the page's two riskiest
 * actions: activating/deactivating a user is the only way to cut off
 * access, and the group filter is the only way to narrow a ~600-row list.
 */
describe('AdminUsersPage error paths', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminUsersPage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideTranslateService({ lang: 'ka', fallbackLang: 'ka' }),
        provideTranslateHttpLoader({ prefix: '/i18n/', suffix: '.json' })
      ]
    }).compileComponents();
  });

  function componentWithStubbedLoads() {
    const usersService = TestBed.inject(AdminUsersService);
    vi.spyOn(usersService, 'list').mockReturnValue(of([]));
    vi.spyOn(usersService, 'groupLeaders').mockReturnValue(of([]));
    const fixture = TestBed.createComponent(AdminUsersPage);
    return { fixture, component: fixture.componentInstance as any, usersService };
  }

  /**
   * The backend returns a human-readable Georgian `detail` for its 400s
   * here -- "you cannot deactivate your own account", "this is the last
   * system admin". Throwing that away and showing a generic message loses
   * the only sentence that tells the admin what to do instead.
   */
  it('surfaces the backend detail when toggling a user status fails', () => {
    const { component, usersService } = componentWithStubbedLoads();
    const toast = TestBed.inject(ToastService);
    const toastSpy = vi.spyOn(toast, 'error');
    vi.spyOn(usersService, 'updateStatus').mockReturnValue(
      throwError(() => new HttpErrorResponse({
        status: 400,
        error: { detail: 'საკუთარი ანგარიშის დეაქტივაცია არ შეიძლება' }
      }))
    );

    component.toggleStatus({ id: 7, is_active: true, email: 'x@magti.ge' });

    expect(toastSpy).toHaveBeenCalledWith('საკუთარი ანგარიშის დეაქტივაცია არ შეიძლება');
  });

  /**
   * ...and the list is reloaded, so the row does not keep showing the state
   * the failed click was aiming for. A toggle that visually succeeded while
   * the server refused is worse than the error itself.
   */
  it('reloads the list after a failed toggle so the displayed state stays truthful', () => {
    const { component, usersService } = componentWithStubbedLoads();
    const listSpy = vi.spyOn(usersService, 'list');
    const callsBefore = listSpy.mock.calls.length;
    vi.spyOn(usersService, 'updateStatus').mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 500 }))
    );

    component.toggleStatus({ id: 7, is_active: true, email: 'x@magti.ge' });

    expect(listSpy.mock.calls.length).toBeGreaterThan(callsBefore);
  });

  /** FE-04: a failed group-leader load must be visible, not an empty dropdown. */
  it('flags a failed group-leader load instead of showing an empty filter', () => {
    const usersService = TestBed.inject(AdminUsersService);
    vi.spyOn(usersService, 'list').mockReturnValue(of([]));
    vi.spyOn(usersService, 'groupLeaders').mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 503 }))
    );

    const fixture = TestBed.createComponent(AdminUsersPage);
    const component = fixture.componentInstance as any;

    expect(component.groupLeadersFailed()).toBe(true);
  });

  it('clears the failure flag once a retry succeeds', () => {
    const { component, usersService } = componentWithStubbedLoads();
    component.groupLeadersFailed.set(true);
    vi.spyOn(usersService, 'groupLeaders').mockReturnValue(of([{ id: 1, name: 'ჯგუფი' } as any]));

    component.loadGroupLeaders();

    expect(component.groupLeadersFailed()).toBe(false);
  });
});

/**
 * PO-24. The sweep switches off real people's access in one click, so the
 * tests here are about the ways it could switch off the wrong ones: an
 * account that has simply never been used, a row the admin can no longer
 * see, and their own.
 */
describe('AdminUsersPage leaver sweep (PO-24)', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminUsersPage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideTranslateService({ lang: 'ka', fallbackLang: 'ka' }),
        provideTranslateHttpLoader({ prefix: '/i18n/', suffix: '.json' })
      ]
    }).compileComponents();
  });

  const day = 24 * 60 * 60 * 1000;
  const daysAgo = (days: number) => new Date(Date.now() - days * day).toISOString();

  function user(overrides: Record<string, unknown>) {
    return {
      id: 1,
      name: 'ტესტი',
      email: 'someone@magti.ge',
      role: 'operator',
      department: 'ტექნიკური',
      is_active: true,
      last_active: daysAgo(1),
      ...overrides
    } as any;
  }

  function pageWith(users: any[]) {
    const usersService = TestBed.inject(AdminUsersService);
    vi.spyOn(usersService, 'list').mockReturnValue(of(users));
    vi.spyOn(usersService, 'groupLeaders').mockReturnValue(of([]));
    const fixture = TestBed.createComponent(AdminUsersPage);
    return { fixture, component: fixture.componentInstance as any, usersService };
  }

  /**
   * The one that would have shipped wrong. Somebody hired last week has no
   * `last_active` at all; if a missing value counted as "longer ago than any
   * threshold", every new hire would sit in the list of leavers the admin is
   * about to tick.
   */
  it('keeps never-signed-in accounts out of the day thresholds', () => {
    const { component } = pageWith([
      user({ id: 1, email: 'gone@magti.ge', last_active: daysAgo(200) }),
      user({ id: 2, email: 'new-hire@magti.ge', last_active: null })
    ]);

    component.inactivityFilter.set('90');
    expect(component.filteredUsers().map((u: any) => u.id)).toEqual([1]);

    component.inactivityFilter.set('never');
    expect(component.filteredUsers().map((u: any) => u.id)).toEqual([2]);
  });

  it('never offers the acting administrator or an already-off account to the sweep', () => {
    const auth = TestBed.inject(AuthService);
    vi.spyOn(auth, 'currentUser').mockReturnValue({ email: 'admin@magti.ge' } as any);
    const { component } = pageWith([
      user({ id: 1, email: 'admin@magti.ge' }),
      user({ id: 2, email: 'off@magti.ge', is_active: false }),
      user({ id: 3, email: 'leaver@magti.ge' })
    ]);

    component.toggleSelectAll();

    expect([...component.selectedIds()]).toEqual([3]);
    component.toggleSelection(user({ id: 1, email: 'admin@magti.ge' }));
    expect([...component.selectedIds()]).toEqual([3]);
  });

  /**
   * Ticks made under one filter are invisible under the next. Deactivating
   * forty accounts while six are on screen is the accident this whole
   * feature exists to avoid.
   */
  it('drops the selection when the filter changes', () => {
    const { component } = pageWith([user({ id: 3, email: 'leaver@magti.ge' })]);
    component.toggleSelectAll();
    expect(component.selectedCount()).toBe(1);

    component.onInactivityFilterChange({ target: { value: '90' } } as any);

    expect(component.selectedCount()).toBe(0);
  });

  it('does not call the backend when the confirmation is declined', async () => {
    const { component, usersService } = pageWith([user({ id: 3, email: 'leaver@magti.ge' })]);
    vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockResolvedValue(false);
    const sweep = vi.spyOn(usersService, 'bulkDeactivate');

    component.toggleSelectAll();
    await component.bulkDeactivate();

    expect(sweep).not.toHaveBeenCalled();
    expect(component.selectedCount()).toBe(1);
  });

  it('surfaces the backend detail when the sweep is refused', async () => {
    const { component, usersService } = pageWith([user({ id: 3, email: 'leaver@magti.ge' })]);
    vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockResolvedValue(true);
    const toastSpy = vi.spyOn(TestBed.inject(ToastService), 'error');
    vi.spyOn(usersService, 'bulkDeactivate').mockReturnValue(
      throwError(() => new HttpErrorResponse({
        status: 400,
        error: { detail: 'არცერთი მომხმარებელი არ არის შესარჩევი' }
      }))
    );

    component.toggleSelectAll();
    await component.bulkDeactivate();

    expect(toastSpy).toHaveBeenCalledWith('არცერთი მომხმარებელი არ არის შესარჩევი');
  });

  it('clears the selection and reloads once the sweep succeeds', async () => {
    const { component, usersService } = pageWith([user({ id: 3, email: 'leaver@magti.ge' })]);
    vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockResolvedValue(true);
    const listSpy = vi.spyOn(usersService, 'list');
    const callsBefore = listSpy.mock.calls.length;
    vi.spyOn(usersService, 'bulkDeactivate').mockReturnValue(
      of({ deactivated: 1, skipped: 0, requested: 1 })
    );

    component.toggleSelectAll();
    await component.bulkDeactivate();

    expect(component.selectedCount()).toBe(0);
    expect(listSpy.mock.calls.length).toBeGreaterThan(callsBefore);
  });
});
