package ge.magti.portal.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key for {@link ArticleTargetDepartment} (article_id,
 * department). Field names must match the {@code @Id}-annotated fields on
 * the entity exactly -- that's how {@code @IdClass} wires the two together.
 */
public class ArticleTargetDepartmentId implements Serializable {

    private Long articleId;
    private String department;

    public ArticleTargetDepartmentId() {
    }

    public ArticleTargetDepartmentId(Long articleId, String department) {
        this.articleId = articleId;
        this.department = department;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ArticleTargetDepartmentId that)) {
            return false;
        }
        return Objects.equals(articleId, that.articleId) && Objects.equals(department, that.department);
    }

    @Override
    public int hashCode() {
        return Objects.hash(articleId, department);
    }
}
