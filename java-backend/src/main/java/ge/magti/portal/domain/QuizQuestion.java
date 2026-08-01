package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors models.py's QuizQuestion (models.py:544-553).
 *
 * <p>{@link #answers} is ordered by {@link QuizAnswer#getPosition()} in
 * Python (the relationship's {@code order_by}) -- this class doesn't
 * enforce that ordering itself; whoever populates the list from storage
 * later is responsible for it, same as the DB query is today.
 */
@Entity
@Table(name = "quiz_questions")
public class QuizQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "article_id", nullable = false)
    private Long articleId;

    @Lob
    @Column(name = "question_text", nullable = false)
    private String questionText;

    @Column(name = "position")
    private int position = 0;

    // @Transient: real repository-query concern, same reasoning as
    // Article.targetDepartments -- not modeled as a JPA relationship yet.
    @Transient
    private List<QuizAnswer> answers = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getArticleId() {
        return articleId;
    }

    public void setArticleId(Long articleId) {
        this.articleId = articleId;
    }

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public List<QuizAnswer> getAnswers() {
        return answers;
    }

    public void setAnswers(List<QuizAnswer> answers) {
        this.answers = answers;
    }
}
