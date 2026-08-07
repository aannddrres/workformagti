import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  ActivityPoint,
  BreakdownItem,
  ComplianceStats,
  CriticalOperatorsResponse,
  DepartmentDashboard,
  GroupUsersResponse,
  KpiCounts,
  PopularSearch,
  UserProgressItem,
} from '../models/stats';

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

  kpi(): Observable<KpiCounts> {
    return this.http.get<KpiCounts>('/api/statistics/kpi');
  }

  activity(days = 7, bucket: 'day' | 'hour' = 'day', category?: string): Observable<ActivityPoint[]> {
    let path = `/api/statistics/activity?days=${days}&bucket=${bucket}`;
    if (category) {
      path += `&category=${encodeURIComponent(category)}`;
    }
    return this.http.get<ActivityPoint[]>(path);
  }

  compliance(): Observable<ComplianceStats> {
    return this.http.get<ComplianceStats>('/api/statistics/compliance');
  }

  popularSearches(): Observable<PopularSearch[]> {
    return this.http.get<PopularSearch[]>('/api/statistics/popular-searches');
  }

  failedSearches(): Observable<PopularSearch[]> {
    return this.http.get<PopularSearch[]>('/api/statistics/failed-searches');
  }

  userProgress(): Observable<UserProgressItem[]> {
    return this.http.get<UserProgressItem[]>('/api/statistics/user-progress');
  }

  breakdown(dimension: 'department' | 'role' | 'status'): Observable<BreakdownItem[]> {
    return this.http.get<BreakdownItem[]>(`/api/statistics/breakdown?dimension=${dimension}`);
  }
}
