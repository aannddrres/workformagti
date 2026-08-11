import { Component, signal } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { QuizQuestionAdmin } from '../../core/models/quiz-admin';

/**
 * Port of app-core.js's addQuizQuestion/addQuizAnswer/collectQuizQuestionsFromDOM
 * (lines 1592-1663): each question has a text + a set of answers, exactly
 * one of which is marked correct via a per-question radio group. State
 * lives in this component's own signal rather than being scraped back out
 * of the DOM on submit, but the shape and validation (>=2 non-empty
 * answers per question, non-empty question text) match exactly.
 */
@Component({
  selector: 'app-quiz-builder',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './quiz-builder.html'
})
export class QuizBuilder {
  protected readonly questions = signal<QuizQuestionAdmin[]>([]);

  /** Called by the parent when opening edit mode for an article that
   *  already has quiz questions, or reset to [] for a fresh create/cancel. */
  setQuestions(questions: QuizQuestionAdmin[]): void {
    this.questions.set(questions.length ? questions : []);
  }

  /** Mirrors collectQuizQuestionsFromDOM: drops empty questions and answers,
   *  requires >=2 non-empty answers per surviving question. */
  getQuestions(): QuizQuestionAdmin[] {
    return this.questions()
      .map((q, qi) => ({
        id: q.id,
        question_text: q.question_text.trim(),
        position: qi,
        answers: q.answers
          .filter((a) => a.answer_text.trim().length > 0)
          .map((a, ai) => ({ ...a, answer_text: a.answer_text.trim(), position: ai }))
      }))
      .filter((q) => q.question_text.length > 0 && q.answers.length >= 2);
  }

  protected addQuestion(): void {
    this.questions.update((qs) => [
      ...qs,
      {
        id: null,
        question_text: '',
        position: qs.length,
        answers: [
          { id: null, answer_text: '', is_correct: true, position: 0 },
          { id: null, answer_text: '', is_correct: false, position: 1 }
        ]
      }
    ]);
  }

  protected removeQuestion(index: number): void {
    this.questions.update((qs) => qs.filter((_, i) => i !== index));
  }

  protected updateQuestionText(index: number, text: string): void {
    this.questions.update((qs) => qs.map((q, i) => (i === index ? { ...q, question_text: text } : q)));
  }

  protected addAnswer(questionIndex: number): void {
    this.questions.update((qs) =>
      qs.map((q, i) =>
        i === questionIndex
          ? { ...q, answers: [...q.answers, { id: null, answer_text: '', is_correct: false, position: q.answers.length }] }
          : q
      )
    );
  }

  protected removeAnswer(questionIndex: number, answerIndex: number): void {
    this.questions.update((qs) =>
      qs.map((q, i) => (i === questionIndex ? { ...q, answers: q.answers.filter((_, ai) => ai !== answerIndex) } : q))
    );
  }

  protected updateAnswerText(questionIndex: number, answerIndex: number, text: string): void {
    this.questions.update((qs) =>
      qs.map((q, i) =>
        i === questionIndex
          ? { ...q, answers: q.answers.map((a, ai) => (ai === answerIndex ? { ...a, answer_text: text } : a)) }
          : q
      )
    );
  }

  protected setCorrectAnswer(questionIndex: number, answerIndex: number): void {
    this.questions.update((qs) =>
      qs.map((q, i) =>
        i === questionIndex
          ? { ...q, answers: q.answers.map((a, ai) => ({ ...a, is_correct: ai === answerIndex })) }
          : q
      )
    );
  }
}
