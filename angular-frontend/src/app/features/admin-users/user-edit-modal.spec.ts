import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';
import { UserEditModal } from './user-edit-modal';
import { AdminUser } from '../../core/models/admin-user';

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

  function modalFor(role: string, permissions: string[] = []) {
    const fixture = TestBed.createComponent(UserEditModal);
    fixture.componentRef.setInput('user', {
      id: 1,
      email: 'x@magti.ge',
      name: 'ტესტი',
      role,
      department: 'All',
      position: '',
      is_active: true,
      permissions
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

  it('labels every switch admin-bypass for a system admin, never role-default', () => {
    const options = allOptions(modalFor('admin'));

    expect(options.length).toBeGreaterThan(0);
    expect(options.every((o: any) => o.lockReason === 'admin-bypass')).toBe(true);
    expect(options.every((o: any) => o.disabled && o.checked)).toBe(true);
  });

  /**
   * The distinction has to survive the case that motivated it: a content
   * admin's locked switches ARE role defaults, and saying so is correct.
   */
  it('still labels a content admin locked switch as a role default', () => {
    const options = allOptions(modalFor('content_admin'));
    const locked = options.filter((o: any) => o.disabled);

    expect(locked.length).toBeGreaterThan(0);
    expect(locked.every((o: any) => o.lockReason === 'role-default')).toBe(true);
  });

  /**
   * ...and a permission a role does NOT grant stays freely toggleable, which
   * is the only case where the switch does real work.
   */
  it('leaves a non-default permission unlocked for a non-admin role', () => {
    const options = allOptions(modalFor('manager'));
    const unlocked = options.filter((o: any) => o.lockReason === 'none');

    expect(unlocked.length).toBeGreaterThan(0);
    expect(unlocked.every((o: any) => !o.disabled)).toBe(true);
  });
});
