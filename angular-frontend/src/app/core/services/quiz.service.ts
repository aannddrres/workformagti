import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { QuizAttemptResult, QuizPublic } from '../models/quiz';

/** Reader-facing quiz endpoints -- GET .../quiz (no is_correct anywhere) +
 *  POST .../quiz/attempt (server-side grading). Distinct from
 *  QuizAdminService, which wraps the admin question-bank editor's
 *  GET/PUT .../quiz/admin. */
@Injectable({ providedIn: 'root' })
export class QuizService {
  private readonly http = inject(HttpClient);

  get(articleId: number): Observable<QuizPublic> {
    return this.http.get<QuizPublic>(`/api/articles/${articleId}/quiz`);
  }

  submit(articleId: number, answers: Record<number, number>): Observable<QuizAttemptResult> {
    return this.http.post<QuizAttemptResult>(`/api/articles/${articleId}/quiz/attempt`, { answers });
  }
}
