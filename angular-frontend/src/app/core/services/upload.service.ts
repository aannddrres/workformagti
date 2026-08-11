import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { UploadResult } from '../models/upload';

/** Wraps POST /api/upload -- the one generic attachment endpoint reused by
 *  the Article/News/Video admin forms (attachments, dropzone, drag-drop,
 *  pasted-image embeds). See web.UploadController. */
@Injectable({ providedIn: 'root' })
export class UploadService {
  private readonly http = inject(HttpClient);

  upload(file: File): Observable<UploadResult> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<UploadResult>('/api/upload', formData);
  }
}
