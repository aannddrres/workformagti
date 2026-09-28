import { DecimalPipe } from '@angular/common';
import { Component, HostListener, OnDestroy, inject, signal } from '@angular/core';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { AuthService } from '../core/auth/auth.service';
import { ThemeService } from '../core/theme/theme.service';
import { Logo } from '../shared/logo/logo';
import { GlobalSearch } from '../shared/global-search/global-search';
import { UserProfileService } from '../core/auth/user-profile.service';
import { FontScaleService } from '../core/accessibility/font-scale.service';
import { IdleSessionService } from '../core/auth/idle-session.service';
import { LoginPage } from '../core/auth/login-page';
import { PortalDialog } from '../shared/portal-dialog/portal-dialog';
import { nameInitials } from '../shared/name-initials';

interface NavLink {
  label: string;
  path: string;
  icon: string;
  allowRoles?: string[];
  denyRoles?: string[];
  /** Show this link when the backend's effective-access response allows it. */
  requiresPermission?: string;
  requiresAnnouncementPublisher?: boolean;
}

interface NavSection {
  label: string;
  links: NavLink[];
  /** Section itself only renders if the user can see at least one link in it. */
}

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [DecimalPipe, GlobalSearch, Logo, RouterOutlet, RouterLink, RouterLinkActive, TranslatePipe, PortalDialog],
  templateUrl: './app-shell.html'
})
export class AppShell implements OnDestroy {
  protected readonly profiles = inject(UserProfileService);
  protected readonly auth = inject(AuthService);
  protected readonly translate = inject(TranslateService);
  protected readonly theme = inject(ThemeService);
  protected readonly fontScale = inject(FontScaleService);
  protected readonly idleSession = inject(IdleSessionService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly loginPage = inject(LoginPage);
  private readonly mobileViewport = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia('(max-width: 1023px)')
    : null;
  private readonly onViewportChange = (event: MediaQueryListEvent): void => this.isMobile.set(event.matches);

  protected readonly mobileMenuOpen = signal(false);
  protected readonly isMobile = signal(this.mobileViewport?.matches ?? false);
  protected readonly sidebarCollapsed = signal(localStorage.getItem('magti_sidebar_collapsed') === 'true');
  protected readonly fontMenuOpen = signal(false);
  protected readonly accountMenuOpen = signal(false);
  protected readonly pageTitle = signal('');

  protected readonly sections: NavSection[] = [
    {
      label: 'nav.sidebar.section_work',
      links: [
        { label: 'nav.sidebar.home', path: '/', icon: 'fa-house' },
        { label: 'nav.sidebar.section_kb', path: '/info', icon: 'fa-book-open' },
        { label: 'nav.sidebar.mandatory_reading', path: '/reading', icon: 'fa-clipboard-check', denyRoles: ['admin', 'content_admin', 'manager'] },
        { label: 'nav.sidebar.news', path: '/news', icon: 'fa-newspaper' },
        { label: 'nav.sidebar.video_instructions', path: '/videos', icon: 'fa-circle-play' },
        { label: 'nav.sidebar.favorites', path: '/favorites', icon: 'fa-star' }
      ]
    },
    {
      label: 'nav.sidebar.section_team',
      links: [
        { label: 'nav.sidebar.team_stats', path: '/manager', icon: 'fa-users', allowRoles: ['admin', 'manager'] }
      ]
    },
    {
      label: 'nav.sidebar.section_admin',
      links: [
        { label: 'nav.sidebar.admin_stats', path: '/admin/overview', icon: 'fa-chart-line', requiresPermission: 'stats.view' },
        { label: 'nav.sidebar.admin_trash', path: '/admin/trash', icon: 'fa-trash-can-arrow-up', requiresPermission: 'content.manage' },
        { label: 'nav.sidebar.admin_categories', path: '/admin/categories', icon: 'fa-folder-tree', requiresPermission: 'content.manage' },
        { label: 'nav.sidebar.admin_broadcasts', path: '/admin/broadcasts', icon: 'fa-bullhorn', requiresAnnouncementPublisher: true },
        { label: 'nav.sidebar.admin_users', path: '/admin/access', icon: 'fa-user-shield', allowRoles: ['admin'] },
        { label: 'nav.sidebar.admin_org', path: '/admin/org', icon: 'fa-sitemap', allowRoles: ['admin'] },
        { label: 'nav.sidebar.admin_assignments', path: '/admin/org/assignments', icon: 'fa-user-tie', allowRoles: ['admin'] },
        { label: 'nav.sidebar.admin_exports', path: '/admin/exports', icon: 'fa-file-export', allowRoles: ['admin'] },
        { label: 'nav.sidebar.admin_logs', path: '/admin/audit', icon: 'fa-shield-halved', allowRoles: ['admin'] },
        { label: 'nav.sidebar.admin_content', path: '/admin/content', icon: 'fa-file-lines', requiresPermission: 'content.manage' }
      ]
    }
  ];

  constructor() {
    this.mobileViewport?.addEventListener('change', this.onViewportChange);
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => {
      this.mobileMenuOpen.set(false);
      this.closeMenus();
      this.updatePageTitle();
    });

    // Warm both display profile and effective access on first paint. Without
    // the latter, permission-driven links would depend on which guard happened
    // to fetch authorization first.
    this.profiles.ensureLoaded().subscribe();
    this.profiles.ensureAccessLoaded().subscribe();
    this.idleSession.start();
    this.updatePageTitle();
  }

  ngOnDestroy(): void {
    this.idleSession.stop();
    this.mobileViewport?.removeEventListener('change', this.onViewportChange);
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
    if (link.requiresPermission && !this.profiles.hasPermission(link.requiresPermission)) {
      return false;
    }
    if (link.requiresAnnouncementPublisher && !this.profiles.canPublishAnnouncement()) {
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
    return nameInitials(this.profiles.profile()?.name, this.auth.currentUser()?.email?.[0] ?? 'M');
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.mobileMenuOpen.set(false);
    this.closeMenus();
  }

  logout(): void {
    this.auth.logout().subscribe(() => this.loginPage.open());
  }

  private updatePageTitle(): void {
    let active = this.route;
    let child = active.firstChild;
    while (child) {
      active = child;
      child = active.firstChild;
    }
    const titleKey = active.snapshot?.data?.['title'] as string | undefined;
    if (!titleKey) {
      this.translate.get('nav.sidebar.home').subscribe((title) => this.pageTitle.set(title));
      return;
    }

    // `instant()` returns the raw key when the async locale file has not
    // arrived yet (most visible on a hard refresh of a deep article link).
    // `get()` waits for the active translation and completes after one value.
    this.translate.get(titleKey).subscribe((title) => this.pageTitle.set(title));
  }
}
