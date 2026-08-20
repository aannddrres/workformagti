import { Component, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AdminUsersPage } from '../admin-users/admin-users-page';
import { AdminRolesPage } from '../admin-roles/admin-roles-page';

type AccessTab = 'users' | 'roles' | 'history';

@Component({
  selector: 'app-admin-access-page',
  standalone: true,
  imports: [AdminUsersPage, AdminRolesPage, RouterLink],
  templateUrl: './admin-access-page.html'
})
export class AdminAccessPage {
  protected readonly activeTab = signal<AccessTab>('users');

  protected selectTab(tab: AccessTab): void {
    this.activeTab.set(tab);
  }
}
