import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';
import { UserEditModal } from './user-edit-modal';
import { AdminUser } from '../../core/models/admin-user';
import { AdminUsersService } from '../../core/services/admin-users.service';
import { of } from 'rxjs';
import { vi } from 'vitest';

/**
 * Why a permission switch is locked is not the same question as whether it
 * is locked, and the modal used to answer both with "(from role)".
 *
 * For a content admin that is true: the role grants it, the permission IS
 * consulted at runtime, and changing the role's defaults would change the
 * outcome. For a system admin it is not: PermissionChecker returns true
 * before it looks at anything (audit SEC-06), so the stored set is never
 * read and no default would change the result. Same lock, different reason,
 * and only one of them is something an administrator can act on.
 */
describe('UserEditModal permission lock reasons', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UserEditModal],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideTranslateService({ lang: 'ka', fallbackLang: 'ka' }),
        provideTranslateHttpLoader({ prefix: '/i18n/', suffix: '.json' })
      ]
    }).compileComponents();
  });

  function modalFor(role: string, overrides: AdminUser['permission_overrides'] = []) {
    const fixture = TestBed.createComponent(UserEditModal);
    fixture.componentRef.setInput('user', {
      id: 1,
      email: 'x@magti.ge',
      name: 'ტესტი',
      role,
      department: 'All',
      position: '',
      is_active: true,
      permissions: [],
      permission_overrides: overrides,
      lock_version: 0
    } as unknown as AdminUser);
    fixture.detectChanges();
    return fixture.componentInstance as any;
  }

  function allOptions(component: any) {
    return component.editPermissionGroups().flatMap((g: any) => g.options);
  }

  it('marks a system admin as bypassing the checks entirely', () => {
    expect(modalFor('admin').bypassesPermissionChecks()).toBe(true);
    expect(modalFor('content_admin').bypassesPermissionChecks()).toBe(false);
    expect(modalFor('manager').bypassesPermissionChecks()).toBe(false);
  });

  it('labels every control admin-bypass for a system admin', () => {
    const options = allOptions(modalFor('admin'));

    expect(options.length).toBeGreaterThan(0);
    expect(options.every((o: any) => o.lockReason === 'admin-bypass')).toBe(true);
    expect(options.every((o: any) => o.disabled && o.effective)).toBe(true);
  });

  /**
   * The distinction has to survive the case that motivated it: a content
   * admin's locked switches ARE role defaults, and saying so is correct.
   */
  it('shows role defaults as inherited without locking out a DENY override', () => {
    const options = allOptions(modalFor('content_admin'));
    const inherited = options.filter((o: any) => o.inherited);

    expect(inherited.length).toBeGreaterThan(0);
    expect(inherited.every((o: any) => o.overrideState === 'INHERIT')).toBe(true);
    expect(inherited.every((o: any) => o.effective && !o.disabled)).toBe(true);
  });

  /**
   * ...and a permission a role does NOT grant stays freely toggleable, which
   * is the only case where the switch does real work.
   */
  it('keeps a non-default permission inactive while inherited', () => {
    const options = allOptions(modalFor('manager'));
    const nonDefaults = options.filter((o: any) => !o.inherited);

    expect(nonDefaults.length).toBeGreaterThan(0);
    expect(nonDefaults.every((o: any) => !o.effective && !o.disabled)).toBe(true);
  });

  it('applies an explicit DENY above a content-admin role default', () => {
    const component = modalFor('content_admin', [{ permission: 'content.manage', state: 'DENY' }]);
    const contentManage = allOptions(component).find((o: any) => o.value === 'content.manage');

    expect(contentManage.inherited).toBe(true);
    expect(contentManage.overrideState).toBe('DENY');
    expect(contentManage.effective).toBe(false);
  });

  it('preserves explicit overrides when the role changes', () => {
    const component = modalFor('operator', [{ permission: 'content.manage', state: 'ALLOW' }]);

    component.onEditRoleChange({ target: { value: 'manager' } } as unknown as Event);

    expect(component.editOverrides().get('content.manage')).toBe('ALLOW');
  });

  it('does not persist inherited role defaults as explicit overrides on ordinary save', () => {
    const service = TestBed.inject(AdminUsersService);
    const component = modalFor('content_admin');
    const updateSpy = vi.spyOn(service, 'update').mockReturnValue(of({ ...component.user(), lock_version: 1 }));
    const permissionSpy = vi.spyOn(service, 'updatePermissions');

    component.submitEdit(new Event('submit'));

    expect(permissionSpy).not.toHaveBeenCalled();
    expect(updateSpy).toHaveBeenCalledWith(1, expect.objectContaining({
      lock_version: 0,
      overrides: []
    }));
  });

  it('atomically sends profile and only the changed override with the original lock version', () => {
    const service = TestBed.inject(AdminUsersService);
    const component = modalFor('operator');
    const updateSpy = vi.spyOn(service, 'update')
      .mockReturnValue(of({ ...component.user(), lock_version: 1 }));
    const permissionSpy = vi.spyOn(service, 'updatePermissions');
    component.setPermissionOverride('content.manage', 'ALLOW');

    component.submitEdit(new Event('submit'));

    expect(permissionSpy).not.toHaveBeenCalled();
    expect(updateSpy).toHaveBeenCalledWith(1, expect.objectContaining({
      lock_version: 0,
      overrides: [{ permission: 'content.manage', state: 'ALLOW' }]
    }));
  });
});
