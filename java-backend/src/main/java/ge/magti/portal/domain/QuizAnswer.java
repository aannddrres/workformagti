package ge.magti.portal.domain;

/**
 * Mirrors models.py's QuizAnswer (models.py:556-564). Plain shape only, no
 * persistence annotations (Phase 1b), same rule as {@link User}.
 *
 * <p><b>{@link #correct} must never reach an operator-facing response.</b>
 * Python enforces this with two separate Pydantic schemas --
 * {@code QuizAnswerAdmin} (includes {@code is_correct}) vs.
 * {@code QuizAnswerPublic} (omits it entirely, schemas.py:322-327) -- rather
 * than a single schema with a hidden field. This entity always carries the
 * flag, same as {@code models.QuizAnswer} always does; whoever writes the
 * operator-facing response DTO later must build a separate public shape
 * that leaves this field out, not just skip serializing it.
 */
public class QuizAnswer {

    private Long id;
    private Long questionId;
    private String answerText;
    private boolean correct = false;
    private int position = 0;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public String getAnswerText() {
        return answerText;
    }

    public void setAnswerText(String answerText) {
        this.answerText = answerText;
    }

    public boolean isCorrect() {
        return correct;
    }

    public void setCorrect(boolean correct) {
        this.correct = correct;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }
}
