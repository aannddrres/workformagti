/** Mirrors web.QuizAnswerPublicDto (java-backend) field-for-field -- no is_correct anywhere. */
export interface QuizAnswerOption {
  id: number;
  answer_text: string;
}

/** Mirrors web.QuizQuestionPublicDto (java-backend) field-for-field. */
export interface QuizQuestionPublic {
  id: number;
  question_text: string;
  answers: QuizAnswerOption[];
}

/** Mirrors web.QuizPublicResponse (java-backend) field-for-field -- the
 *  reader-facing GET .../quiz shape, distinct from QuizAdminView (which
 *  carries is_correct for the admin question-bank editor). */
export interface QuizPublic {
  article_id: number;
  article_version: number;
  questions: QuizQuestionPublic[];
}

/** Mirrors web.QuizAttemptResultResponse (java-backend) field-for-field. */
export interface QuizAttemptResult {
  passed: boolean;
  score: number;
  total_questions: number;
  wrong_question_ids: number[];
  attempt_number: number;
}
