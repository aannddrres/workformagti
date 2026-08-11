import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { VideoInstruction, VideoInstructionRequest } from '../models/video';

@Injectable({ providedIn: 'root' })
export class VideosService {
  private readonly http = inject(HttpClient);

  /** GET /api/videos -- no pagination params; the backend already returns
   *  only the operator's visible (department-matched, non-archived) set,
   *  or everything for admins/content_admins. Matches the original's
   *  single-shot fetch (no skip/limit exists on this endpoint). */
  list(): Observable<VideoInstruction[]> {
    return this.http.get<VideoInstruction[]>('/api/videos');
  }

  create(request: VideoInstructionRequest): Observable<VideoInstruction> {
    return this.http.post<VideoInstruction>('/api/videos', request);
  }

  update(id: number, request: VideoInstructionRequest): Observable<VideoInstruction> {
    return this.http.put<VideoInstruction>(`/api/videos/${id}`, request);
  }

  remove(id: number): Observable<void> {
    return this.http.delete<void>(`/api/videos/${id}`);
  }

  /** Fire-and-forget view-tracking, matching viewVideo's "log the view,
   *  don't block the player" behavior -- fired on opening the detail view,
   *  not on actually pressing play (which is unobservable for a
   *  cross-origin YouTube iframe anyway). */
  logView(id: number): void {
    this.http.post<void>(`/api/videos/${id}/view`, {}).subscribe({ error: () => void 0 });
  }
}
