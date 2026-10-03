package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;

/**
 * An operator's explicit "I have read this" acknowledgment, distinct from
 * {@link ArticleViewLog}'s passive open-tracking.
 *
 * <p>The four {@code *Snapshot} fields are deliberately denormalized
 * copies (article title, operator name/email/department) taken at
 * read-receipt time, not live joins, so a receipt survives the
 * article or user being deleted later while the historical facts stay
 * readable. Plain fields for that reason, not a
 * relationship to {@link Article}/{@link User}.
 *
 * <p>Enforcement note (not implemented here, DB-dependent): a receipt can
 * only be recorded once {@link Article#isQuizEnabled()} is satisfied by a
 * passing {@link QuizAttempt} at the article's current version
 * -- see {@link QuizAttempt}'s Javadoc for
 * how known bug #2 affects that check.
 */
@Entity
@Table(name = "article_read_receipts", uniqueConstraints = @UniqueConstraint(
        name = "uq_article_read_receipt_version_operator",
        columnNames = {"article_id", "article_version", "operator_id"}))
public class ArticleReadReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "article_id")
    private Long articleId;

    /**
     * BL-12. {@link #articleId} is a foreign key with {@code ON DELETE SET
     * NULL}, so deleting the article erased the only thing the read paths
     * filter on -- the row survived and became unreachable. This copy has no
     * foreign key, so nothing nulls it, and it is what the queries use.
     * Keeping both is the point: {@code articleId} still answers "does that
     * article still exist", which is a different question from "which
     * article was this".
     */
    @Column(name = "article_id_snapshot")
    private Long articleIdSnapshot;

    @Column(name = "article_title_snapshot", nullable = false, length = 500)
    private String articleTitleSnapshot;

    @Column(name = "article_version", nullable = false)
    private int articleVersion;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "operator_name_snapshot", nullable = false, length = 200)
    private String operatorNameSnapshot;

    @Column(name = "operator_email_snapshot", nullable = false, length = 255)
    private String operatorEmailSnapshot;

    @Column(name = "operator_department_snapshot", length = 200)
    private String operatorDepartmentSnapshot;

    @Column(name = "read_at", nullable = false)
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

    public Long getArticleIdSnapshot() {
        return articleIdSnapshot;
    }

    public void setArticleIdSnapshot(Long articleIdSnapshot) {
        this.articleIdSnapshot = articleIdSnapshot;
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
