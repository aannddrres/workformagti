/** Mirrors web.QuizAnswerAdminDto. */
export interface QuizAnswerAdmin {
  id: number | null;
  answer_text: string;
  is_correct: boolean;
  position: number;
}

/** Mirrors web.QuizQuestionAdminDto. */
export interface QuizQuestionAdmin {
  id: number | null;
  question_text: string;
  position: number;
  answers: QuizAnswerAdmin[];
}

/** Mirrors web.QuizAdminUpdate -- shared GET response / PUT request shape. */
export interface QuizAdminView {
  questions: QuizQuestionAdmin[];
}
