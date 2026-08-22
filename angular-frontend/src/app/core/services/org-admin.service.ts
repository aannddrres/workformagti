import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  LeadershipAssignment,
  LeadershipAssignmentCreateRequest,
  OrgBackfillReport,
  OrgStructure,
  PolicyShadowSnapshot
} from '../models/org-admin';

@Injectable({ providedIn: 'root' })
export class OrgAdminService {
  private readonly http = inject(HttpClient);

  structure(): Observable<OrgStructure> {
    return this.http.get<OrgStructure>('/api/admin/org/structure');
  }

  assignments(): Observable<LeadershipAssignment[]> {
    return this.http.get<LeadershipAssignment[]>('/api/admin/org/assignments');
  }

  createAssignment(request: LeadershipAssignmentCreateRequest): Observable<LeadershipAssignment> {
    return this.http.post<LeadershipAssignment>('/api/admin/org/assignments', request);
  }

  deactivateAssignment(assignmentId: number): Observable<LeadershipAssignment> {
    return this.http.delete<LeadershipAssignment>(`/api/admin/org/assignments/${assignmentId}`);
  }

  policyShadow(): Observable<PolicyShadowSnapshot> {
    return this.http.get<PolicyShadowSnapshot>('/api/admin/policy-shadow');
  }

  backfillReport(): Observable<OrgBackfillReport> {
    return this.http.get<OrgBackfillReport>('/api/admin/org-backfill/report');
  }

  applyBackfill(): Observable<OrgBackfillReport> {
    return this.http.post<OrgBackfillReport>('/api/admin/org-backfill/apply', {});
  }
}
