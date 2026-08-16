import { Component, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { AuthService } from '../core/auth/auth.service';
import { ThemeService } from '../core/theme/theme.service';
import { Logo } from '../shared/logo/logo';
import { GlobalSearch } from '../shared/global-search/global-search';
import { UserProfileService } from '../core/auth/user-profile.service';

interface NavLink {
  labelKey: string;
  path: string;
  allowRoles?: string[];
  denyRoles?: string[];
  /**
   * Show this link when the backend says the user holds the permission,
   * regardless of role. Roles cannot express the audit trail's real rule —
   * MANAGER holds `system.audit` and gets a department-scoped view — and a
   * role list here would be a second, drifting copy of a decision the server
   * already makes.
   */
  requiresAuditLog?: boolean;
}

interface NavSection {
  labelKey: string;
  links: NavLink[];
  /** Section itself only renders if the user can see at least one link in it. */
}

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [GlobalSearch, Logo, RouterOutlet, RouterLink, RouterLinkActive, TranslatePipe],
  templateUrl: './app-shell.html'
})
export class AppShell {
  private readonly profiles = inject(UserProfileService);
  protected readonly auth = inject(AuthService);
  protected readonly translate = inject(TranslateService);
  protected readonly theme = inject(ThemeService);
  private readonly router = inject(Router);

  protected readonly mobileMenuOpen = signal(false);

  protected readonly sections: NavSection[] = [
    {
      labelKey: 'nav.sidebar.section_org',
      links: [
        { labelKey: 'nav.sidebar.home', path: '/' },
        { labelKey: 'nav.sidebar.team_stats', path: '/manager', allowRoles: ['admin', 'manager'] }
      ]
    },
    {
      labelKey: 'nav.sidebar.section_kb',
      links: [
        { labelKey: 'nav.sidebar.section_kb', path: '/info' },
        { labelKey: 'nav.sidebar.mandatory_reading', path: '/reading', denyRoles: ['admin', 'content_admin', 'manager'] },
        { labelKey: 'nav.sidebar.video_instructions', path: '/videos' },
        { labelKey: 'nav.sidebar.news', path: '/news' }
      ]
    },
    {
      labelKey: 'nav.sidebar.section_personal',
      links: [
        { labelKey: 'nav.sidebar.favorites', path: '/favorites' },
        { labelKey: 'nav.sidebar.personal_cabinet', path: '/profile' }
      ]
    },
    {
      labelKey: 'nav.sidebar.section_admin',
      links: [
        { labelKey: 'nav.sidebar.admin_stats', path: '/admin/main', allowRoles: ['admin', 'content_admin'] },
        { labelKey: 'nav.sidebar.admin_content', path: '/admin/content', allowRoles: ['admin', 'content_admin'] },
        { labelKey: 'nav.sidebar.admin_categories', path: '/admin/categories', allowRoles: ['admin', 'content_admin'] },
        { labelKey: 'nav.sidebar.admin_users', path: '/admin/users', allowRoles: ['admin'] },
        { labelKey: 'nav.sidebar.admin_roles', path: '/admin/roles', allowRoles: ['admin'] },
        { labelKey: 'nav.sidebar.admin_logs', path: '/admin/audit', requiresAuditLog: true }
      ]
    }
  ];

  constructor() {
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => this.mobileMenuOpen.set(false));

    // Warm the profile so the sidebar can decide about permission-driven links
    // on first paint. Without it the audit entry would appear only once
    // something else happened to fetch the profile — exactly the kind of
    // order-dependent visibility that let FE-06 sit unnoticed.
    this.profiles.ensureLoaded().subscribe();
  }

  toggleMobileMenu(): void {
    this.mobileMenuOpen.update((open) => !open);
  }

  closeMobileMenu(): void {
    this.mobileMenuOpen.set(false);
  }

  canSee(link: NavLink): boolean {
    const role = this.auth.currentUser()?.role;
    if (!role) {
      return false;
    }
    if (link.allowRoles && !link.allowRoles.includes(role)) {
      return false;
    }
    if (link.denyRoles && link.denyRoles.includes(role)) {
      return false;
    }
    if (link.requiresAuditLog && !this.profiles.canViewAuditLog()) {
      return false;
    }
    return true;
  }

  sectionVisible(section: NavSection): boolean {
    return section.links.some((link) => this.canSee(link));
  }

  toggleLanguage(): void {
    const next = this.translate.currentLang() === 'ka' ? 'en' : 'ka';
    this.translate.use(next);
  }

  logout(): void {
    this.auth.logout().subscribe(() => this.router.navigateByUrl('/login'));
  }
}
