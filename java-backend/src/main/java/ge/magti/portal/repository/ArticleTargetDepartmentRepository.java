package ge.magti.portal.repository;

import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.domain.ArticleTargetDepartmentId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ArticleTargetDepartmentRepository
        extends JpaRepository<ArticleTargetDepartment, ArticleTargetDepartmentId> {

    List<ArticleTargetDepartment> findByArticleId(Long articleId, Pageable pageable);

    List<ArticleTargetDepartment> findByArticleIdIn(Collection<Long> articleIds, Pageable pageable);

    // Article.targetDepartmentRows is @Transient (not a real JPA
    // association) -- create/update replace the
    // whole set explicitly: delete then re-insert.
    void deleteByArticleId(Long articleId);
}
