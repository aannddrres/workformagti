import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AdminUser, BulkRoleReassignResponse, GroupLeader, UserAdminUpdateRequest, UserCreateRequest } from '../models/admin-user';

@Injectable({ providedIn: 'root' })
export class AdminUsersService {
  private readonly http = inject(HttpClient);

  list(managerId?: number | null): Observable<AdminUser[]> {
    let params = new HttpParams();
    if (managerId) {
      params = params.set('manager_id', managerId);
    }
    return this.http.get<AdminUser[]>('/api/users', { params });
  }

  groupLeaders(): Observable<GroupLeader[]> {
    return this.http.get<GroupLeader[]>('/api/admin/group-leaders');
  }

  create(request: UserCreateRequest): Observable<AdminUser> {
    return this.http.post<AdminUser>('/api/users', request);
  }

  update(userId: number, request: UserAdminUpdateRequest): Observable<AdminUser> {
    return this.http.put<AdminUser>(`/api/users/${userId}`, request);
  }

  updateStatus(userId: number, active: boolean): Observable<AdminUser> {
    return this.http.put<AdminUser>(`/api/users/${userId}/status`, { is_active: active });
  }

  updatePermissions(userId: number, permissions: string[]): Observable<AdminUser> {
    return this.http.put<AdminUser>(`/api/users/${userId}/permissions`, { permissions });
  }

  bulkReassignRole(userIds: number[], newRole: string): Observable<BulkRoleReassignResponse> {
    return this.http.post<BulkRoleReassignResponse>('/api/admin/roles/bulk-reassign', {
      user_ids: userIds,
      new_role: newRole
    });
  }
}
