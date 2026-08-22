import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { CurrentUserProfile, EffectiveAccess } from '../models/user';

@Injectable({ providedIn: 'root' })
export class UsersService {
  private readonly http = inject(HttpClient);

  me(): Observable<CurrentUserProfile> {
    return this.http.get<CurrentUserProfile>('/api/users/me');
  }

  effectiveAccess(): Observable<EffectiveAccess> {
    return this.http.get<EffectiveAccess>('/api/me/effective-access');
  }
}
