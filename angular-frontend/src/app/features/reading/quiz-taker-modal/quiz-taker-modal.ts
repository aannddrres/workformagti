import { Component, effect, inject, input, output, signal } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { QuizService } from '../../../core/services/quiz.service';
import { QuizPublic } from '../../../core/models/quiz';
import { PortalDialog } from '../../../shared/portal-dialog/portal-dialog';

/**
 * Reader-facing quiz modal -- port of app-core.js's #quiz-modal
 * (ensureQuizModal/openQuizModal/submitQuizAttempt, app-core.js:6244-6354).
 * Radio-button questions, submit, and on a wrong attempt only the wrong
 * questions' selections are cleared (a correct pick elsewhere isn't lost).
 * Emits {@link passed} on success so the caller (MyReadingsPage) can retry
 * its own mark-read call -- this component only owns the quiz itself, not
 * the compliance mark-read side effect.
 */
@Component({
  selector: 'app-quiz-taker-modal',
  standalone: true,
  imports: [TranslatePipe, PortalDialog],
  templateUrl: './quiz-taker-modal.html'
})
export class QuizTakerModal {
  private readonly quizService = inject(QuizService);

  readonly articleId = input.required<number>();
  readonly closed = output<void>();
  readonly passed = output<void>();

  protected readonly quiz = signal<QuizPublic | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal(false);

  protected readonly selectedAnswers = signal<Map<number, number>>(new Map());
  protected readonly submitting = signal(false);
  protected readonly wrongQuestionIds = signal<Set<number>>(new Set());
  protected readonly feedback = signal<string | null>(null);

  constructor() {
    // Required signal inputs aren't readable synchronously in the
    // constructor body (NG0950) -- effect() defers this read, same
    // pattern as ArticleHistoryModal/ArticleVersionHistoryOverlay.
    effect(() => {
      this.load(this.articleId());
    });
  }

  private load(articleId: number): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.quizService.get(articleId).subscribe({
      next: (data) => {
        this.quiz.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set(true);
        this.loading.set(false);
      }
    });
  }

  protected selectAnswer(questionId: number, answerId: number): void {
    const next = new Map(this.selectedAnswers());
    next.set(questionId, answerId);
    this.selectedAnswers.set(next);
  }

  protected isSelected(questionId: number, answerId: number): boolean {
    return this.selectedAnswers().get(questionId) === answerId;
  }

  protected submit(): void {
    const quiz = this.quiz();
    if (!quiz) {
      return;
    }
    const answers: Record<number, number> = {};
    this.selectedAnswers().forEach((answerId, questionId) => {
      answers[questionId] = answerId;
    });

    this.submitting.set(true);
    this.feedback.set(null);
    this.quizService.submit(quiz.article_id, answers).subscribe({
      next: (result) => {
        this.submitting.set(false);
        if (result.passed) {
          this.passed.emit();
        } else {
          this.wrongQuestionIds.set(new Set(result.wrong_question_ids));
          const next = new Map(this.selectedAnswers());
          result.wrong_question_ids.forEach((id) => next.delete(id));
          this.selectedAnswers.set(next);
          this.feedback.set('wrong');
        }
      },
      error: () => {
        this.submitting.set(false);
        this.feedback.set('error');
      }
    });
  }

  protected close(): void {
    this.closed.emit();
  }
}
