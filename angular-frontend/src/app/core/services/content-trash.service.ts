import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ContentTrashItem, TrashItemType } from '../models/content-trash';

@Injectable({ providedIn: 'root' })
export class ContentTrashService {
  private readonly http = inject(HttpClient);

  list(): Observable<ContentTrashItem[]> {
    return this.http.get<ContentTrashItem[]>('/api/content-trash');
  }

  restore(type: TrashItemType, id: number): Observable<{ detail: string }> {
    return this.http.post<{ detail: string }>(`/api/content-trash/${type}/${id}/restore`, {});
  }

  purge(type: TrashItemType, id: number): Observable<{ detail: string }> {
    return this.http.delete<{ detail: string }>(`/api/content-trash/${type}/${id}`);
  }
}
