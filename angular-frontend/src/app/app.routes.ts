import { Routes } from '@angular/router';
import { AppShell } from './shell/app-shell';
import { authGuard } from './core/auth/auth.guard';
import { announcementPublisherGuard, contentManageGuard } from './core/auth/permission.guard';
import { adminOverviewGuard, roleGuard } from './core/auth/role.guard';
import { landingGuard } from './core/auth/landing.guard';
// The role sets live in one file now rather than being re-declared per
// consumer. The guards themselves stayed on this side of the merge: the
// admin area moved from role lists to capability guards (contentManageGuard,
// adminOverviewGuard), so auditLogGuard no longer exists to import.
import { ADMIN_ONLY, MANAGEMENT_ROLES, MANAGER_ROLES } from './core/auth/roles';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/login/login').then(({ Login }) => Login)
  },
  {
    path: '',
    component: AppShell,
    canActivate: [authGuard],
    children: [
      // 11 main pages (page-dashboard .. page-admin in base-layout.html)
      {
        path: '',
        loadComponent: () => import('./features/dashboard/dashboard-page').then(({ DashboardPage }) => DashboardPage),
        canActivate: [landingGuard],
        data: { title: 'nav.sidebar.home' }
      },
      {
        path: 'manager',
        loadComponent: () => import('./features/team-stats/team-stats-page').then(({ TeamStatsPage }) => TeamStatsPage),
        data: { title: 'nav.sidebar.team_stats' },
        canActivate: [roleGuard(MANAGER_ROLES)]
      },
      { path: 'info', loadComponent: () => import('./features/knowledge-base/knowledge-base-page').then(({ KnowledgeBasePage }) => KnowledgeBasePage), data: { title: 'nav.sidebar.section_kb' } },
      {
        path: 'reading',
        loadComponent: () => import('./features/reading/my-readings-page').then(({ MyReadingsPage }) => MyReadingsPage),
        data: { title: 'nav.sidebar.mandatory_reading' },
        canActivate: [roleGuard(undefined, MANAGEMENT_ROLES)]
      },
      { path: 'videos', loadComponent: () => import('./features/videos/videos-page').then(({ VideosPage }) => VideosPage), data: { title: 'nav.sidebar.video_instructions' } },
      { path: 'news', loadComponent: () => import('./features/news/news-page').then(({ NewsPage }) => NewsPage), data: { title: 'nav.sidebar.news' } },
      { path: 'favorites', loadComponent: () => import('./features/favorites/favorites-page').then(({ FavoritesPage }) => FavoritesPage), data: { title: 'nav.sidebar.favorites' } },

      // page-article-view / page-category-view deep-link detail routes
      { path: 'article/:id', loadComponent: () => import('./features/article-detail/article-detail-page').then(({ ArticleDetailPage }) => ArticleDetailPage), data: { title: 'nav.sidebar.section_kb' } },
      { path: 'category/:slug', loadComponent: () => import('./features/category-view/category-view-page').then(({ CategoryViewPage }) => CategoryViewPage), data: { title: 'nav.sidebar.section_kb' } },
      { path: 'news/:id', loadComponent: () => import('./features/news/news-detail-page').then(({ NewsDetailPage }) => NewsDetailPage), data: { title: 'nav.sidebar.news' } },
      { path: 'videos/:id', loadComponent: () => import('./features/videos/video-detail-page').then(({ VideoDetailPage }) => VideoDetailPage), data: { title: 'nav.sidebar.video_instructions' } },

      // page-profile's 5 tabs -- modeled as real child routes (bookmarkable),
      // unlike the current app's client-side-only tab switching.
      {
        path: 'profile',
        children: [
          { path: '', loadComponent: () => import('./features/account/account-page').then(({ AccountPage }) => AccountPage), data: { title: 'users.profile.tab_profile' } },
          { path: 'favorites', pathMatch: 'full', redirectTo: '/favorites' },
          { path: 'settings', pathMatch: 'full', redirectTo: '' },
          { path: 'search-history', pathMatch: 'full', redirectTo: '' }
        ]
      },

      // page-admin's 6 real sub-panels (a 7th, "migrated", is dead code in
      // app-router.js -- no admin-migrated panel exists in base-layout.html).
      // Deliberately OUTSIDE the content.manage-gated /admin block below.
      //
      {
        path: 'admin/audit',
        loadComponent: () => import('./features/admin-audit/admin-audit-page').then(({ AdminAuditPage }) => AdminAuditPage),
        canActivate: [roleGuard(ADMIN_ONLY)],
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
        path: 'admin/exports',
        loadComponent: () =>
          import('./features/admin-exports/admin-exports-page').then(
            ({ AdminExportsPage }) => AdminExportsPage
          ),
        canActivate: [roleGuard(ADMIN_ONLY)],
        data: { title: 'მონაცემების ექსპორტი' }
      },
      {
        path: 'admin',
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'overview' },
          {
            path: 'overview',
            loadComponent: () => import('./features/admin-stats/admin-stats-page').then(({ AdminStatsPage }) => AdminStatsPage),
            data: { title: 'nav.sidebar.admin_stats' },
            canActivate: [adminOverviewGuard]
          },
          { path: 'main', pathMatch: 'full', redirectTo: 'overview' },
          { path: 'content', loadComponent: () => import('./features/admin-content/admin-content-page').then(({ AdminContentPage }) => AdminContentPage), data: { title: 'nav.sidebar.admin_content' }, canActivate: [contentManageGuard] },
          {
            path: 'trash',
            loadComponent: () =>
              import('./features/admin-trash/admin-trash-page').then(({ AdminTrashPage }) => AdminTrashPage),
            data: { title: 'სანაგვე' },
            canActivate: [contentManageGuard]
          },
          { path: 'categories', loadComponent: () => import('./features/admin-categories/admin-categories-page').then(({ AdminCategoriesPage }) => AdminCategoriesPage), data: { title: 'nav.sidebar.admin_categories' }, canActivate: [contentManageGuard] },
          {
            path: 'access',
            loadComponent: () => import('./features/admin-access/admin-access-page').then(({ AdminAccessPage }) => AdminAccessPage),
            data: { title: 'nav.sidebar.admin_users' },
            canActivate: [roleGuard(ADMIN_ONLY)]
          },
          {
            path: 'org/assignments',
            loadComponent: () => import('./features/admin-org/admin-assignments-page').then(({ AdminAssignmentsPage }) => AdminAssignmentsPage),
            data: { title: 'ლიდერების დანიშვნა' },
            canActivate: [roleGuard(ADMIN_ONLY)]
          },
          {
            path: 'org/backfill',
            loadComponent: () => import('./features/admin-org/admin-backfill-page').then(({ AdminBackfillPage }) => AdminBackfillPage),
            data: { title: 'Backfill კონტროლი' },
            canActivate: [roleGuard(ADMIN_ONLY)]
          },
          {
            path: 'org',
            loadComponent: () => import('./features/admin-org/admin-org-page').then(({ AdminOrgPage }) => AdminOrgPage),
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
      },
      {
        path: 'forbidden',
        loadComponent: () => import('./features/status/status-page').then(({ StatusPage }) => StatusPage),
        data: { status: 403, title: 'წვდომა შეზღუდულია' }
      },
      {
        path: '**',
        loadComponent: () => import('./features/status/status-page').then(({ StatusPage }) => StatusPage),
        data: { status: 404, title: 'გვერდი ვერ მოიძებნა' }
      }
    ]
  },
  { path: '**', redirectTo: 'login' }
];
