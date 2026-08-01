package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Mirrors models.py's QuizAnswer (models.py:556-564).
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
@Entity
@Table(name = "quiz_answers")
public class QuizAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "answer_text", nullable = false, length = 1000)
    private String answerText;

    @Column(name = "is_correct")
    private boolean correct = false;

    @Column(name = "position")
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
