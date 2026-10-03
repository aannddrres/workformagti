import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { AuthService } from '../../core/auth/auth.service';
import { Logo } from '../../shared/logo/logo';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [Logo, TranslatePipe],
  templateUrl: './login.html'
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly translate = inject(TranslateService);

  protected readonly submitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  /**
   * Why the portal sent this person here, when it did: the idle timer
   * (IdleSessionService) or an account switched off mid-session
   * (unauthorizedInterceptor). Both passed it in the address for months and
   * this page never read it, so the one explanation either gets was lost
   * (blind tests, 2026-10-02).
   */
  protected readonly reason = this.route.snapshot.queryParamMap.get('reason');

  /**
   * The only way in. The address and password go to the portal, which
   * checks them with the company directory -- this page never talks to the
   * directory itself. In development the backend's test accounts sign in
   * through this same form (APP_ENV + ALLOW_DEV_LOGIN, never in production).
   * The server's `detail` is shown as it comes: one sentence for any refusal
   * (PO-26), another when the directory is unreachable, so a person can tell
   * "try again later" from "check your password".
   */
  submit(event: Event, email: string, password: string): void {
    event.preventDefault();
    if (this.submitting()) return;
    const address = email.trim().toLowerCase();
    if (!address || !password) {
      this.errorMessage.set(this.translate.instant('auth.login_page.error_missing'));
      return;
    }
    this.submitting.set(true);
    this.errorMessage.set(null);
    this.auth.login(address, password).subscribe({
      next: (user) => {
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl');
        this.router.navigateByUrl(returnUrl || this.defaultWorkspace(user.role));
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        this.errorMessage.set(err.error?.detail ?? this.translate.instant('auth.login_page.error_generic'));
      }
    });
  }

  private defaultWorkspace(role: string): string {
    if (role === 'manager') return '/manager';
    if (role === 'content_admin') return '/admin/content';
    if (role === 'admin') return '/admin/overview';
    return '/';
  }
}
