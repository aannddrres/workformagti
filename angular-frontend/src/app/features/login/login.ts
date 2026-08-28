import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslatePipe } from '@ngx-translate/core';
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

  protected readonly submitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly isLocal = ['localhost', '127.0.0.1', '::1'].includes(window.location.hostname);
  protected readonly personas = [
    { email: 'nino@magti.ge', label: 'ოპერატორი', icon: 'fa-headset' },
    { email: 'manager@magti.ge', label: 'მენეჯერი', icon: 'fa-users' },
    { email: 'content@magti.ge', label: 'კონტენტ-ადმინი', icon: 'fa-file-pen' },
    { email: 'admin@magti.ge', label: 'სისტემური ადმინი', icon: 'fa-shield-halved' }
  ];

  loginPersona(email: string): void {
    if (this.submitting()) return;
    this.submitting.set(true);
    this.errorMessage.set(null);
    this.auth.loginPersona(email).subscribe({
      next: (user) => {
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl');
        this.router.navigateByUrl(returnUrl || this.defaultWorkspace(user.role));
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        this.errorMessage.set(err.error?.detail || null);
      }
    });
  }

  startCorporateSso(): void {
    if (this.submitting()) return;
    this.submitting.set(true);
    this.errorMessage.set(null);
    this.auth.startCorporateSso().subscribe({
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        this.errorMessage.set(err.error?.detail ?? 'კომპანიის ავტორიზაციის სერვისი მიუწვდომელია. წვდომა უსაფრთხოების მიზნით არ გაიცა.');
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
