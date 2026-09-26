import { TestBed } from '@angular/core/testing';
import { provideHttpClient, HttpErrorResponse } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { Login } from './login';
import { AuthService } from '../../core/auth/auth.service';

/**
 * The company login form (every host but loopback). The page hands the
 * address and password to the portal and shows the server's sentence as it
 * comes -- it never talks to the directory itself.
 */
describe('Login company form', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Login],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideTranslateService({ lang: 'ka', fallbackLang: 'ka' }),
        provideTranslateHttpLoader({ prefix: '/i18n/', suffix: '.json' })
      ]
    }).compileComponents();
  });

  function component() {
    return TestBed.createComponent(Login).componentInstance as any;
  }

  it('sends the trimmed, lower-cased address with the password and opens the role workspace', () => {
    const auth = TestBed.inject(AuthService);
    const login = vi.spyOn(auth, 'login').mockReturnValue(of({ email: 'a.b@magticom.ge', role: 'manager' }));
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    component().submitCorporate(new Event('submit'), '  A.B@Magticom.ge ', 'secret-pw');

    expect(login).toHaveBeenCalledWith('a.b@magticom.ge', 'secret-pw');
    expect(navigate).toHaveBeenCalledWith('/manager');
  });

  /**
   * One sentence for a refusal (PO-26), another when the directory is down.
   * Replacing either with a generic message would erase the difference
   * between "check your password" and "try again later".
   */
  it('shows the server sentence as it comes', () => {
    vi.spyOn(TestBed.inject(AuthService), 'login').mockReturnValue(throwError(() => new HttpErrorResponse({
      status: 503,
      error: { detail: 'კომპანიის ავტორიზაციის სერვისი დროებით მიუწვდომელია. სცადეთ მოგვიანებით.' }
    })));
    const page = component();

    page.submitCorporate(new Event('submit'), 'a.b@magticom.ge', 'pw');

    expect(page.errorMessage()).toBe('კომპანიის ავტორიზაციის სერვისი დროებით მიუწვდომელია. სცადეთ მოგვიანებით.');
    expect(page.submitting()).toBe(false);
  });

  /**
   * ASVS V6.2.6/V6.2.7: masked entry, and nothing that fights a password
   * manager -- no autocomplete="off", no blocked paste.
   */
  it('masks the password and leaves it to the browser password manager', () => {
    const fixture = TestBed.createComponent(Login);
    (fixture.componentInstance as any).isLocal = false;
    fixture.detectChanges();
    const password = fixture.nativeElement.querySelector('#login-password') as HTMLInputElement;

    expect(password.type).toBe('password');
    expect(password.getAttribute('autocomplete')).toBe('current-password');
    const paste = new Event('paste', { bubbles: true, cancelable: true });
    password.dispatchEvent(paste);
    expect(paste.defaultPrevented).toBe(false);
  });

  /** ASVS V6.2.8: only the address is normalised; the password goes as typed. */
  it('sends the password exactly as typed', () => {
    const login = vi.spyOn(TestBed.inject(AuthService), 'login').mockReturnValue(of({ email: 'a.b@magticom.ge', role: 'operator' }));
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const typed = '  Pass Word ქართული  ';

    component().submitCorporate(new Event('submit'), 'a.b@magticom.ge', typed);

    expect(login).toHaveBeenCalledWith('a.b@magticom.ge', typed);
  });

  it('does not send an empty address or password', () => {
    const login = vi.spyOn(TestBed.inject(AuthService), 'login');
    const page = component();

    page.submitCorporate(new Event('submit'), '   ', 'pw');
    page.submitCorporate(new Event('submit'), 'a.b@magticom.ge', '');

    expect(login).not.toHaveBeenCalled();
    expect(page.errorMessage()).toBeTruthy();
  });
});
