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

  it('does not send an empty address or password', () => {
    const login = vi.spyOn(TestBed.inject(AuthService), 'login');
    const page = component();

    page.submitCorporate(new Event('submit'), '   ', 'pw');
    page.submitCorporate(new Event('submit'), 'a.b@magticom.ge', '');

    expect(login).not.toHaveBeenCalled();
    expect(page.errorMessage()).toBeTruthy();
  });
});
