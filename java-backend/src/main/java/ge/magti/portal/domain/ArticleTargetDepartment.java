package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Mirrors models.py's ArticleTargetDepartment (models.py:189-198) -- the
 * junction table letting one {@link Article} target multiple departments
 * (or "All"). Coexists with {@code Article.targetDepartment} (the legacy
 * single-value column) during the migration window -- see {@link
 * Article}'s own javadoc on why both are carried over unchanged.
 */
@Entity
@Table(name = "article_target_departments")
@IdClass(ArticleTargetDepartmentId.class)
public class ArticleTargetDepartment {

    @Id
    @Column(name = "article_id")
    private Long articleId;

    @Id
    @Column(name = "department", length = 200)
    private String department;

    public Long getArticleId() {
        return articleId;
    }

    public void setArticleId(Long articleId) {
        this.articleId = articleId;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }
}
