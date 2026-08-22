import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AdminExportFamily } from '../models/admin-export';
import { ExportJobResponse } from '../models/export';

@Injectable({ providedIn: 'root' })
export class AdminExportService {
  private readonly http = inject(HttpClient);

  submit(family: AdminExportFamily, from?: string, through?: string): Observable<ExportJobResponse> {
    let params = new HttpParams();
    if (from) params = params.set('from', from);
    if (through) params = params.set('through', through);
    return this.http.post<ExportJobResponse>(`/api/admin/exports/${family}`, null, { params });
  }
}
