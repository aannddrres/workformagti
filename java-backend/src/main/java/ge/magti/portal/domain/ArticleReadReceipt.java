package ge.magti.portal.domain;

import java.time.OffsetDateTime;

/**
 * Mirrors models.py's ArticleReadReceipt (models.py:486-509) -- an
 * operator's explicit "I have read this" acknowledgment, distinct from
 * {@code ArticleViewLog}'s passive open-tracking (not ported in this step).
 * Plain shape only, no persistence annotations (Phase 1b), same rule as
 * {@link User}.
 *
 * <p>The four {@code *Snapshot} fields are deliberately denormalized
 * copies (article title, operator name/email/department) taken at
 * read-receipt time, not live joins -- Python's FKs are
 * {@code ondelete="SET NULL"} specifically so a receipt survives the
 * article or user being deleted later while the historical facts stay
 * readable. Carried over as plain fields for the same reason, not a
 * relationship to {@link Article}/{@link User}.
 *
 * <p>Enforcement note (not implemented here, DB-dependent): a receipt can
 * only be recorded once {@link Article#isQuizEnabled()} is satisfied by a
 * passing {@link QuizAttempt} at the article's current version
 * (routers/articles.py:1020-1029) -- see {@link QuizAttempt}'s Javadoc for
 * how known bug #2 affects that check.
 */
public class ArticleReadReceipt {

    private Long id;
    private Long articleId;
    private String articleTitleSnapshot;
    private int articleVersion;
    private Long operatorId;
    private String operatorNameSnapshot;
    private String operatorEmailSnapshot;
    private String operatorDepartmentSnapshot;
    private OffsetDateTime readAt;

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

    public String getArticleTitleSnapshot() {
        return articleTitleSnapshot;
    }

    public void setArticleTitleSnapshot(String articleTitleSnapshot) {
        this.articleTitleSnapshot = articleTitleSnapshot;
    }

    public int getArticleVersion() {
        return articleVersion;
    }

    public void setArticleVersion(int articleVersion) {
        this.articleVersion = articleVersion;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getOperatorNameSnapshot() {
        return operatorNameSnapshot;
    }

    public void setOperatorNameSnapshot(String operatorNameSnapshot) {
        this.operatorNameSnapshot = operatorNameSnapshot;
    }

    public String getOperatorEmailSnapshot() {
        return operatorEmailSnapshot;
    }

    public void setOperatorEmailSnapshot(String operatorEmailSnapshot) {
        this.operatorEmailSnapshot = operatorEmailSnapshot;
    }

    public String getOperatorDepartmentSnapshot() {
        return operatorDepartmentSnapshot;
    }

    public void setOperatorDepartmentSnapshot(String operatorDepartmentSnapshot) {
        this.operatorDepartmentSnapshot = operatorDepartmentSnapshot;
    }

    public OffsetDateTime getReadAt() {
        return readAt;
    }

    public void setReadAt(OffsetDateTime readAt) {
        this.readAt = readAt;
    }
}
