import { Routes } from '@angular/router';
import { AppShell } from './shell/app-shell';
import { Login } from './features/login/login';
import { KnowledgeBasePage } from './features/knowledge-base/knowledge-base-page';
import { DashboardPage } from './features/dashboard/dashboard-page';
import { CategoryViewPage } from './features/category-view/category-view-page';
import { ArticleDetailPage } from './features/article-detail/article-detail-page';
import { NewsPage } from './features/news/news-page';
import { NewsDetailPage } from './features/news/news-detail-page';
import { VideosPage } from './features/videos/videos-page';
import { VideoDetailPage } from './features/videos/video-detail-page';
import { FavoritesPage } from './features/favorites/favorites-page';
import { MyReadingsPage } from './features/reading/my-readings-page';
import { TeamStatsPage } from './features/team-stats/team-stats-page';
import { AdminStatsPage } from './features/admin-stats/admin-stats-page';
import { AdminAuditPage } from './features/admin-audit/admin-audit-page';
import { AdminContentPage } from './features/admin-content/admin-content-page';
import { AdminCategoriesPage } from './features/admin-categories/admin-categories-page';
import { AdminAccessPage } from './features/admin-access/admin-access-page';
import { AdminOrgPage } from './features/admin-org/admin-org-page';
import { AdminAssignmentsPage } from './features/admin-org/admin-assignments-page';
import { AdminBackfillPage } from './features/admin-org/admin-backfill-page';
import { AccountPage } from './features/account/account-page';
import { authGuard } from './core/auth/auth.guard';
import { announcementPublisherGuard, auditLogGuard, contentManageGuard } from './core/auth/permission.guard';
import { adminOverviewGuard, roleGuard } from './core/auth/role.guard';

const MANAGER_ROLES = ['admin', 'manager'];
const MANAGEMENT_ROLES = ['admin', 'content_admin', 'manager'];
const ADMIN_ONLY = ['admin'];

export const routes: Routes = [
  { path: 'login', component: Login },
  {
    path: '',
    component: AppShell,
    canActivate: [authGuard],
    children: [
      // 11 main pages (page-dashboard .. page-admin in base-layout.html)
      { path: '', component: DashboardPage, data: { title: 'nav.sidebar.home' } },
      {
        path: 'manager',
        component: TeamStatsPage,
        data: { title: 'nav.sidebar.team_stats' },
        canActivate: [roleGuard(MANAGER_ROLES)]
      },
      { path: 'info', component: KnowledgeBasePage, data: { title: 'nav.sidebar.section_kb' } },
      {
        path: 'reading',
        component: MyReadingsPage,
        data: { title: 'nav.sidebar.mandatory_reading' },
        canActivate: [roleGuard(undefined, MANAGEMENT_ROLES)]
      },
      { path: 'videos', component: VideosPage, data: { title: 'nav.sidebar.video_instructions' } },
      { path: 'news', component: NewsPage, data: { title: 'nav.sidebar.news' } },
      { path: 'favorites', component: FavoritesPage, data: { title: 'nav.sidebar.favorites' } },

      // page-article-view / page-category-view deep-link detail routes
      { path: 'article/:id', component: ArticleDetailPage, data: { title: 'nav.sidebar.section_kb' } },
      { path: 'category/:slug', component: CategoryViewPage, data: { title: 'nav.sidebar.section_kb' } },
      { path: 'news/:id', component: NewsDetailPage, data: { title: 'nav.sidebar.news' } },
      { path: 'videos/:id', component: VideoDetailPage, data: { title: 'nav.sidebar.video_instructions' } },

      // page-profile's 5 tabs -- modeled as real child routes (bookmarkable),
      // unlike the current app's client-side-only tab switching.
      {
        path: 'profile',
        children: [
          { path: '', component: AccountPage, data: { title: 'users.profile.tab_profile' } },
          { path: 'favorites', pathMatch: 'full', redirectTo: '/favorites' },
          { path: 'settings', pathMatch: 'full', redirectTo: '' },
          { path: 'search-history', pathMatch: 'full', redirectTo: '' }
        ]
      },

      // page-admin's 6 real sub-panels (a 7th, "migrated", is dead code in
      // app-router.js -- no admin-migrated panel exists in base-layout.html).
      // Deliberately OUTSIDE the content.manage-gated /admin block below.
      //
      // The backend grants MANAGER `system.audit` (Permission.java:45) and
      // serves them a department-scoped audit view
      // (AuditLogController:205-207), but the old role-gated parent bounced
      // managers before any of that ran,
      // so the scoping code had no reachable caller in production (audit
      // FE-06). Matched before 'admin' so /admin/audit resolves here.
      {
        path: 'admin/audit',
        component: AdminAuditPage,
        canActivate: [auditLogGuard],
        data: { title: 'nav.sidebar.admin_logs' }
      },
      {
        path: 'admin/broadcasts',
        loadComponent: () =>
          import('./features/admin-broadcasts/admin-broadcasts-page').then(
            ({ AdminBroadcastsPage }) => AdminBroadcastsPage
          ),
        canActivate: [announcementPublisherGuard],
        data: { title: 'განცხადებების მართვა' }
      },
      {
        path: 'admin',
        canActivate: [contentManageGuard],
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'overview' },
          {
            path: 'overview',
            component: AdminStatsPage,
            data: { title: 'nav.sidebar.admin_stats' },
            canActivate: [adminOverviewGuard]
          },
          { path: 'main', pathMatch: 'full', redirectTo: 'overview' },
          { path: 'content', component: AdminContentPage, data: { title: 'nav.sidebar.admin_content' } },
          { path: 'categories', component: AdminCategoriesPage, data: { title: 'nav.sidebar.admin_categories' } },
          {
            path: 'access',
            component: AdminAccessPage,
            data: { title: 'nav.sidebar.admin_users' },
            canActivate: [roleGuard(ADMIN_ONLY)]
          },
          {
            path: 'org/assignments',
            component: AdminAssignmentsPage,
            data: { title: 'ლიდერების დანიშვნა' },
            canActivate: [roleGuard(ADMIN_ONLY)]
          },
          {
            path: 'org/backfill',
            component: AdminBackfillPage,
            data: { title: 'Backfill კონტროლი' },
            canActivate: [roleGuard(ADMIN_ONLY)]
          },
          {
            path: 'org',
            component: AdminOrgPage,
            data: { title: 'ორგანიზაციული სტრუქტურა' },
            canActivate: [roleGuard(ADMIN_ONLY)]
          },
          {
            path: 'users',
            pathMatch: 'full',
            redirectTo: 'access'
          },
          {
            path: 'roles',
            pathMatch: 'full',
            redirectTo: 'access'
          }
        ]
      }
    ]
  },
  { path: '**', redirectTo: '' }
];
