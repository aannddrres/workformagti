import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslatePipe } from '@ngx-translate/core';
import { AuthService } from '../../core/auth/auth.service';
import { Logo } from '../../shared/logo/logo';

type DemoRole = 'admin' | 'content_admin' | 'manager' | 'operator';

/** Two-digit zero pad, matching the seeder's g{NN}/op{NN} naming. */
const pad = (value: number): string => String(value).padStart(2, '0');

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

  /**
   * The demo org has one system admin, four content admins, ten group leaders
   * (five საინფორმაციო + five ტექნიკური) and ten operators per group. Four
   * fixed persona buttons could not reach any of them, so this is a cascading
   * picker instead: role, then — for a leader or operator — department and
   * group, then which operator. Every choice resolves to a seeded account the
   * backend accepts with the persona password (see {@link loginPersona}); the
   * `presentation.` accounts ride the same dev-login gate as the six named
   * personas, so nothing new is exposed off loopback.
   */
  protected readonly roleOptions: ReadonlyArray<{ value: DemoRole; label: string; icon: string }> = [
    { value: 'operator', label: 'ოპერატორი', icon: 'fa-headset' },
    { value: 'manager', label: 'ჯგუფის უფროსი', icon: 'fa-users' },
    { value: 'content_admin', label: 'კონტენტ-ადმინი', icon: 'fa-file-pen' },
    { value: 'admin', label: 'სისტემური ადმინი', icon: 'fa-shield-halved' }
  ];
  protected readonly deptOptions: ReadonlyArray<{ value: string; label: string }> = [
    { value: 'tech', label: 'ტექნიკური' },
    { value: 'info', label: 'საინფორმაციო' }
  ];
  protected readonly groups = [1, 2, 3, 4, 5];
  protected readonly operatorSlots = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
  protected readonly contentSlots = [1, 2, 3, 4];

  protected readonly role = signal<DemoRole | null>(null);
  protected readonly dept = signal<string | null>(null);
  protected readonly group = signal<number | null>(null);
  protected readonly person = signal<number | null>(null);

  /** True once the current role has every choice it needs to name one account. */
  protected readonly canSubmit = computed(() => this.resolvedEmail() !== null);

  /** The chosen role's selections resolved to a seeded e-mail, or null if incomplete. */
  protected readonly resolvedEmail = computed<string | null>(() => {
    const role = this.role();
    if (role === 'admin') {
      return 'admin@magti.ge';
    }
    if (role === 'content_admin') {
      const slot = this.person();
      if (!slot) return null;
      return slot === 1 ? 'content@magti.ge' : `content${slot}@magti.ge`;
    }
    const short = this.dept();
    const group = this.group();
    if (role === 'manager') {
      if (!short || !group) return null;
      if (short === 'tech' && group === 1) return 'manager@magti.ge';
      return `presentation.${short}.g${pad(group)}.lead@magti.ge`;
    }
    if (role === 'operator') {
      const slot = this.person();
      if (!short || !group || !slot) return null;
      if (short === 'tech' && group === 1 && slot === 1) return 'tech@magti.ge';
      if (short === 'info' && group === 1 && slot === 1) return 'info@magti.ge';
      return `presentation.${short}.g${pad(group)}.op${pad(slot)}@magti.ge`;
    }
    return null;
  });

  protected setRole(role: DemoRole): void {
    this.role.set(role);
    this.dept.set(null);
    this.group.set(null);
    this.person.set(null);
    this.errorMessage.set(null);
  }

  protected setDept(value: string): void {
    this.dept.set(value || null);
    this.group.set(null);
    this.person.set(null);
  }

  protected setGroup(value: string): void {
    this.group.set(value ? Number(value) : null);
    this.person.set(null);
  }

  protected setPerson(value: string): void {
    this.person.set(value ? Number(value) : null);
  }

  protected submitSelected(): void {
    const email = this.resolvedEmail();
    if (!email) {
      this.errorMessage.set('აირჩიეთ ანგარიში ბოლომდე');
      return;
    }
    this.loginPersona(email);
  }

  /**
   * Direct sign-in by address, kept as an escape hatch alongside the picker.
   * Reuses {@link loginPersona} rather than adding a second authentication
   * path; gated by the same `isLocal` check as everything else here.
   */
  loginByEmail(email: string): void {
    const address = email.trim().toLowerCase();
    if (!address) {
      this.errorMessage.set('შეიყვანეთ ელფოსტა');
      return;
    }
    this.loginPersona(address);
  }

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
