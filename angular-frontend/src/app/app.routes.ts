import { Routes } from '@angular/router';
import { AppShell } from './shell/app-shell';
import { PlaceholderPage } from './shell/placeholder-page';
import { Login } from './features/login/login';
import { KnowledgeBasePage } from './features/knowledge-base/knowledge-base-page';
import { DashboardPage } from './features/dashboard/dashboard-page';
import { CategoryViewPage } from './features/category-view/category-view-page';
import { ArticleDetailPage } from './features/article-detail/article-detail-page';
import { authGuard } from './core/auth/auth.guard';
import { roleGuard } from './core/auth/role.guard';

const MANAGER_ROLES = ['admin', 'manager'];
const MANAGEMENT_ROLES = ['admin', 'content_admin', 'manager'];
const ADMIN_OR_CONTENT_ADMIN = ['admin', 'content_admin'];
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
        component: PlaceholderPage,
        data: { title: 'nav.sidebar.team_stats' },
        canActivate: [roleGuard(MANAGER_ROLES)]
      },
      { path: 'info', component: KnowledgeBasePage, data: { title: 'nav.sidebar.section_kb' } },
      {
        path: 'reading',
        component: PlaceholderPage,
        data: { title: 'nav.sidebar.mandatory_reading' },
        canActivate: [roleGuard(undefined, MANAGEMENT_ROLES)]
      },
      { path: 'videos', component: PlaceholderPage, data: { title: 'nav.sidebar.video_instructions' } },
      { path: 'news', component: PlaceholderPage, data: { title: 'nav.sidebar.news' } },
      { path: 'favorites', component: PlaceholderPage, data: { title: 'nav.sidebar.favorites' } },

      // page-article-view / page-category-view deep-link detail routes
      { path: 'article/:id', component: ArticleDetailPage, data: { title: 'nav.sidebar.section_kb' } },
      { path: 'category/:slug', component: CategoryViewPage, data: { title: 'nav.sidebar.section_kb' } },

      // page-profile's 5 tabs -- modeled as real child routes (bookmarkable),
      // unlike the current app's client-side-only tab switching.
      {
        path: 'profile',
        children: [
          { path: '', component: PlaceholderPage, data: { title: 'users.profile.tab_profile' } },
          { path: 'favorites', component: PlaceholderPage, data: { title: 'users.profile.tab_favorites' } },
          { path: 'messages', component: PlaceholderPage, data: { title: 'users.profile.tab_messages' } },
          { path: 'settings', component: PlaceholderPage, data: { title: 'users.profile.tab_settings' } },
          { path: 'search-history', component: PlaceholderPage, data: { title: 'search.history_tab.heading' } }
        ]
      },

      // page-admin's 6 real sub-panels (a 7th, "migrated", is dead code in
      // app-router.js -- no admin-migrated panel exists in base-layout.html).
      {
        path: 'admin',
        canActivate: [roleGuard(ADMIN_OR_CONTENT_ADMIN)],
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'main' },
          { path: 'main', component: PlaceholderPage, data: { title: 'nav.sidebar.admin_stats' } },
          { path: 'content', component: PlaceholderPage, data: { title: 'nav.sidebar.admin_content' } },
          { path: 'categories', component: PlaceholderPage, data: { title: 'nav.sidebar.admin_categories' } },
          {
            path: 'users',
            component: PlaceholderPage,
            data: { title: 'nav.sidebar.admin_users' },
            canActivate: [roleGuard(ADMIN_ONLY)]
          },
          {
            path: 'roles',
            component: PlaceholderPage,
            data: { title: 'nav.sidebar.admin_roles' },
            canActivate: [roleGuard(ADMIN_ONLY)]
          },
          { path: 'audit', component: PlaceholderPage, data: { title: 'nav.sidebar.admin_logs' } }
        ]
      }
    ]
  },
  { path: '**', redirectTo: '' }
];
