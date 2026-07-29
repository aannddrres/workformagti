package ge.magti.portal.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors models.py's QuizQuestion (models.py:544-553). Plain shape only,
 * no persistence annotations (Phase 1b), same rule as {@link User}.
 *
 * <p>{@link #answers} is ordered by {@link QuizAnswer#getPosition()} in
 * Python (the relationship's {@code order_by}) -- this class doesn't
 * enforce that ordering itself; whoever populates the list from storage
 * later is responsible for it, same as the DB query is today.
 */
public class QuizQuestion {

    private Long id;
    private Long articleId;
    private String questionText;
    private int position = 0;
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
