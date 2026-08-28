package ge.magti.portal.article;

import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Complete-result boundary for article audience relations.
 *
 * <p>Parent article sets are already bounded by their list/search callers,
 * but the junction-table child cross-product was not. Every production read
 * now goes through one 1,001-row sentinel query and either receives the whole
 * relation (up to 1,000 rows total) or fails loudly; no caller can silently
 * build an unbounded article-to-departments map.
 */
@Service
public class ArticleTargetQueryService {

    private final ArticleTargetDepartmentRepository repository;

    public ArticleTargetQueryService(ArticleTargetDepartmentRepository repository) {
        this.repository = repository;
    }

    public Map<Long, List<String>> targetDepartmentsByArticleWithinLimit(Collection<Long> articleIds) {
        if (articleIds.isEmpty()) {
            return Map.of();
        }
        return CompleteResultGuard.enforce(
                        repository.findByArticleIdIn(articleIds, CompleteResultGuard.sentinelPage()))
                .stream()
                .collect(Collectors.groupingBy(
                        ArticleTargetDepartment::getArticleId,
                        Collectors.mapping(ArticleTargetDepartment::getDepartment, Collectors.toList())));
    }

    public List<String> targetDepartmentsForArticleWithinLimit(Long articleId) {
        return targetDepartmentsByArticleWithinLimit(List.of(articleId))
                .getOrDefault(articleId, List.of());
    }
}
