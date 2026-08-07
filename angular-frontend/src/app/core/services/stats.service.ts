import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { CriticalOperatorsResponse, DepartmentDashboard, GroupUsersResponse } from '../models/stats';

@Injectable({ providedIn: 'root' })
export class StatsService {
  private readonly http = inject(HttpClient);

  departmentDashboard(): Observable<DepartmentDashboard> {
    return this.http.get<DepartmentDashboard>('/api/manager/department-stats');
  }

  criticalOperators(): Observable<CriticalOperatorsResponse> {
    return this.http.get<CriticalOperatorsResponse>('/api/admin/critical-operators');
  }

  groupUsers(department: string, groupName: string): Observable<GroupUsersResponse> {
    const path = `/api/admin/departments/${encodeURIComponent(department)}/groups/${encodeURIComponent(groupName)}/users`;
    return this.http.get<GroupUsersResponse>(path);
  }
}
