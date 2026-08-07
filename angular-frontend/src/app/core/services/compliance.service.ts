import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { MyProgress, MyReading } from '../models/compliance';

@Injectable({ providedIn: 'root' })
export class ComplianceService {
  private readonly http = inject(HttpClient);

  myProgress(): Observable<MyProgress> {
    return this.http.get<MyProgress>('/api/compliance/my-progress');
  }

  myReadings(): Observable<MyReading[]> {
    return this.http.get<MyReading[]>('/api/compliance/my-readings');
  }
}
