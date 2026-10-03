import { vi } from 'vitest';
import { of, throwError } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';
import { QuizTakerModal } from './quiz-taker-modal';
import { QuizService } from '../../../core/services/quiz.service';
import { QuizPublic } from '../../../core/models/quiz';

const QUIZ: QuizPublic = {
  article_id: 5,
  article_version: 1,
  questions: [
    { id: 1, question_text: 'Q1', answers: [{ id: 10, answer_text: 'A' }, { id: 11, answer_text: 'B' }] },
    { id: 2, question_text: 'Q2', answers: [{ id: 20, answer_text: 'C' }, { id: 21, answer_text: 'D' }] }
  ]
};

/**
 * Covers submit() (quiz-taker-modal.ts:71-101): pass/fail/error outcomes.
 * quiz() is set directly rather than driven through load()/QuizService.get(),
 * since the constructor effect that calls load() needs a change-detection
 * flush this test doesn't perform -- submit() itself doesn't depend on how
 * quiz() got populated.
 */
describe('QuizTakerModal submit', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [QuizTakerModal],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideTranslateService({ lang: 'ka', fallbackLang: 'ka' }),
        provideTranslateHttpLoader({ prefix: '/i18n/', suffix: '.json' })
      ]
    }).compileComponents();
  });

  it('emits passed on a fully correct attempt', () => {
    const fixture = TestBed.createComponent(QuizTakerModal);
    fixture.componentRef.setInput('articleId', 5);
    const component = fixture.componentInstance as any;

    const quizService = TestBed.inject(QuizService);
    vi.spyOn(quizService, 'submit').mockReturnValue(
      of({ passed: true, score: 2, total_questions: 2, wrong_question_ids: [], attempt_number: 1 })
    );

    const passedSpy = vi.fn();
    component.passed.subscribe(passedSpy);

    component.quiz.set(QUIZ);
    component.selectedAnswers.set(new Map([[1, 10], [2, 20]]));

    component.submit();

    expect(passedSpy).toHaveBeenCalledTimes(1);
    expect(component.submitting()).toBe(false);
  });

  it('keeps correct selections and clears only the wrong ones on a failed attempt', () => {
    const fixture = TestBed.createComponent(QuizTakerModal);
    fixture.componentRef.setInput('articleId', 5);
    const component = fixture.componentInstance as any;

    const quizService = TestBed.inject(QuizService);
    vi.spyOn(quizService, 'submit').mockReturnValue(
      of({ passed: false, score: 1, total_questions: 2, wrong_question_ids: [2], attempt_number: 1 })
    );

    component.quiz.set(QUIZ);
    component.selectedAnswers.set(new Map([[1, 10], [2, 20]]));

    component.submit();

    expect(component.feedback()).toBe('wrong');
    expect(component.wrongQuestionIds()).toEqual(new Set([2]));
    expect(component.selectedAnswers().get(1)).toBe(10);
    expect(component.selectedAnswers().has(2)).toBe(false);
  });

  it('sets feedback to error when the submit request fails', () => {
    const fixture = TestBed.createComponent(QuizTakerModal);
    fixture.componentRef.setInput('articleId', 5);
    const component = fixture.componentInstance as any;

    const quizService = TestBed.inject(QuizService);
    vi.spyOn(quizService, 'submit').mockReturnValue(throwError(() => new Error('network error')));

    component.quiz.set(QUIZ);
    component.selectedAnswers.set(new Map([[1, 10], [2, 20]]));

    component.submit();

    expect(component.feedback()).toBe('error');
    expect(component.submitting()).toBe(false);
  });
  it('shows the wait, not a failure, after three failed attempts (PO-55)', () => {
    const fixture = TestBed.createComponent(QuizTakerModal);
    fixture.componentRef.setInput('articleId', 5);
    const component = fixture.componentInstance as any;

    const quizService = TestBed.inject(QuizService);
    vi.spyOn(quizService, 'submit').mockReturnValue(throwError(() => ({
      status: 429,
      error: { code: 'quiz_cooldown', retry_after_seconds: '540' }
    })));

    component.quiz.set(QUIZ);
    component.selectedAnswers.set(new Map([[1, 10], [2, 20]]));

    component.submit();

    expect(component.feedback()).toBe('cooldown');
    expect(component.cooldownMinutes()).toBe(9);
    expect(component.submitting()).toBe(false);
  });
});
