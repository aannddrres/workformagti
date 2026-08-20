import { DecimalPipe } from '@angular/common';
import { Component, HostListener, inject, signal } from '@angular/core';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { AuthService } from '../core/auth/auth.service';
import { ThemeService } from '../core/theme/theme.service';
import { Logo } from '../shared/logo/logo';
import { GlobalSearch } from '../shared/global-search/global-search';
import { UserProfileService } from '../core/auth/user-profile.service';
import { FontScaleService } from '../core/accessibility/font-scale.service';

interface NavLink {
  label: string;
  path: string;
  icon: string;
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
  label: string;
  links: NavLink[];
  /** Section itself only renders if the user can see at least one link in it. */
}

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [DecimalPipe, GlobalSearch, Logo, RouterOutlet, RouterLink, RouterLinkActive, TranslatePipe],
  templateUrl: './app-shell.html'
})
export class AppShell {
  protected readonly profiles = inject(UserProfileService);
  protected readonly auth = inject(AuthService);
  protected readonly translate = inject(TranslateService);
  protected readonly theme = inject(ThemeService);
  protected readonly fontScale = inject(FontScaleService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly mobileMenuOpen = signal(false);
  protected readonly sidebarCollapsed = signal(localStorage.getItem('magti_sidebar_collapsed') === 'true');
  protected readonly fontMenuOpen = signal(false);
  protected readonly accountMenuOpen = signal(false);
  protected readonly pageTitle = signal('მთავარი');

  protected readonly sections: NavSection[] = [
    {
      label: 'სამუშაო',
      links: [
        { label: 'მთავარი', path: '/', icon: 'fa-house' },
        { label: 'ცოდნის ბაზა', path: '/info', icon: 'fa-book-open' },
        { label: 'სავალდებულო გაცნობა', path: '/reading', icon: 'fa-clipboard-check', denyRoles: ['admin', 'content_admin', 'manager'] },
        { label: 'სიახლეები', path: '/news', icon: 'fa-newspaper' },
        { label: 'ვიდეო ინსტრუქციები', path: '/videos', icon: 'fa-circle-play' },
        { label: 'რჩეულები', path: '/favorites', icon: 'fa-star' }
      ]
    },
    {
      label: 'გუნდი',
      links: [
        { label: 'გუნდის მდგომარეობა', path: '/manager', icon: 'fa-users', allowRoles: ['admin', 'manager'] }
      ]
    },
    {
      label: 'ადმინისტრირება',
      links: [
        { label: 'მიმოხილვა', path: '/admin/overview', icon: 'fa-gauge-high', allowRoles: ['admin', 'content_admin'] },
        { label: 'კონტენტი', path: '/admin/content', icon: 'fa-file-lines', allowRoles: ['admin', 'content_admin'] },
        { label: 'კატეგორიები', path: '/admin/categories', icon: 'fa-folder-tree', allowRoles: ['admin', 'content_admin'] },
        { label: 'მომხმარებლები და წვდომა', path: '/admin/access', icon: 'fa-user-shield', allowRoles: ['admin'] },
        { label: 'აუდიტი და უსაფრთხოება', path: '/admin/audit', icon: 'fa-shield-halved', requiresAuditLog: true }
      ]
    }
  ];

  constructor() {
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => {
      this.mobileMenuOpen.set(false);
      this.closeMenus();
      this.updatePageTitle();
    });

    // Warm the profile so the sidebar can decide about permission-driven links
    // on first paint. Without it the audit entry would appear only once
    // something else happened to fetch the profile — exactly the kind of
    // order-dependent visibility that let FE-06 sit unnoticed.
    this.profiles.ensureLoaded().subscribe();
    this.updatePageTitle();
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

  toggleSidebar(): void {
    this.sidebarCollapsed.update((collapsed) => {
      localStorage.setItem('magti_sidebar_collapsed', String(!collapsed));
      return !collapsed;
    });
  }

  toggleFontMenu(): void {
    this.accountMenuOpen.set(false);
    this.fontMenuOpen.update((open) => !open);
  }

  toggleAccountMenu(): void {
    this.fontMenuOpen.set(false);
    this.accountMenuOpen.update((open) => !open);
  }

  closeMenus(): void {
    this.fontMenuOpen.set(false);
    this.accountMenuOpen.set(false);
  }

  roleLabel(): string {
    const role = this.profiles.profile()?.role ?? this.auth.currentUser()?.role;
    return ({
      admin: 'სისტემური ადმინი',
      content_admin: 'კონტენტ-ადმინი',
      manager: 'ჯგუფის უფროსი',
      operator: 'ოპერატორი'
    } as Record<string, string>)[role ?? ''] ?? 'მომხმარებელი';
  }

  initials(): string {
    const name = this.profiles.profile()?.name?.trim();
    if (name) {
      return name.split(/\s+/).slice(0, 2).map((part) => part[0]).join('').toUpperCase();
    }
    return (this.auth.currentUser()?.email?.[0] ?? 'M').toUpperCase();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closeMenus();
  }

  logout(): void {
    this.auth.logout().subscribe(() => this.router.navigateByUrl('/login'));
  }

  private updatePageTitle(): void {
    let active = this.route;
    let child = active.firstChild;
    while (child) {
      active = child;
      child = active.firstChild;
    }
    const titleKey = active.snapshot?.data?.['title'] as string | undefined;
    this.pageTitle.set(titleKey ? this.translate.instant(titleKey) : 'მთავარი');
  }
}
