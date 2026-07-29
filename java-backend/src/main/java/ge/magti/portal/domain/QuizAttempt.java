package ge.magti.portal.domain;

import java.time.OffsetDateTime;

/**
 * Mirrors models.py's QuizAttempt (models.py:567-587) -- every submission,
 * pass or fail. Plain shape only, no persistence annotations (Phase 1b),
 * same rule as {@link User}.
 *
 * <p>{@link #attemptNumber} and the pass/fail gate are both scoped to
 * {@link #articleVersion}, not just {@link #articleId}
 * (routers/articles.py:838-841,1022-1027) -- editing an article's content
 * is supposed to re-require its quiz. This is exactly where known bug #2
 * (decided fix, 2026-07-29) lives: routers/articles.py's quiz-edit handler
 * (:730-765) never increments {@code Article.version}, so a QuizAttempt
 * recorded as {@code passed=true} against the *old* quiz content keeps
 * satisfying the gate check after the quiz questions/answers change --
 * because the version number the gate compares against never moved. Not
 * fixed here: the fix is "bump {@code Article.version} when quiz content
 * changes," which belongs to the future quiz-edit service method, not this
 * data class.
 *
 * <p>{@link #attemptNumber} itself (count of prior attempts for this
 * user+article+version, plus one) and the pass/fail gate query are both
 * DB-dependent lookups (routers/articles.py:838-843,1022-1027) --
 * deliberately not ported here; see {@link ge.magti.portal.quiz.QuizGrader}
 * for the one part of quiz submission that doesn't need a database
 * (scoring the answers themselves).
 */
public class QuizAttempt {

    private Long id;
    private Long articleId;
    private int articleVersion;
    private Long userId;
    private int attemptNumber;
    private int score;
    private int totalQuestions;
    private boolean passed = false;
    private OffsetDateTime createdAt;

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

    public int getArticleVersion() {
        return articleVersion;
    }

    public void setArticleVersion(int articleVersion) {
        this.articleVersion = articleVersion;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public void setAttemptNumber(int attemptNumber) {
        this.attemptNumber = attemptNumber;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public int getTotalQuestions() {
        return totalQuestions;
    }

    public void setTotalQuestions(int totalQuestions) {
        this.totalQuestions = totalQuestions;
    }

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
