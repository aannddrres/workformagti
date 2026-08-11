import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { QuizAdminView, QuizQuestionAdmin } from '../models/quiz-admin';

/** Wraps GET/PUT /api/articles/{id}/quiz/admin -- see web.QuizController. */
@Injectable({ providedIn: 'root' })
export class QuizAdminService {
  private readonly http = inject(HttpClient);

  get(articleId: number): Observable<QuizAdminView> {
    return this.http.get<QuizAdminView>(`/api/articles/${articleId}/quiz/admin`);
  }

  update(articleId: number, questions: QuizQuestionAdmin[]): Observable<QuizAdminView> {
    return this.http.put<QuizAdminView>(`/api/articles/${articleId}/quiz/admin`, { questions });
  }
}
