package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Mirrors models.py's ArticleViewLog (models.py:512-541) -- one row per
 * article open (passive view), distinct from {@link ArticleReadReceipt}'s
 * explicit "I have read this" acknowledgment. No dedup on
 * (article, operator): repeat views are informative, so every open counts
 * -- unlike {@link ArticleReadReceipt}, there is no unique constraint here.
 */
@Entity
@Table(name = "article_view_logs")
public class ArticleViewLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "article_id")
    private Long articleId;

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

    @Column(name = "viewed_at", nullable = false)
    private OffsetDateTime viewedAt;

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

    public OffsetDateTime getViewedAt() {
        return viewedAt;
    }

    public void setViewedAt(OffsetDateTime viewedAt) {
        this.viewedAt = viewedAt;
    }
}
