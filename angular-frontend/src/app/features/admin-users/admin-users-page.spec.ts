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
