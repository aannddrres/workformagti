package ge.magti.portal.repository;

import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.domain.ArticleTargetDepartmentId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArticleTargetDepartmentRepository
        extends JpaRepository<ArticleTargetDepartment, ArticleTargetDepartmentId> {
}
