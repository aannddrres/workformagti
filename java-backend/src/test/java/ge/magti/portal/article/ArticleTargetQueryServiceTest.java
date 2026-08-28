package ge.magti.portal.article;

import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleTargetQueryServiceTest {

    private final ArticleTargetDepartmentRepository repository = mock(ArticleTargetDepartmentRepository.class);
    private final ArticleTargetQueryService service = new ArticleTargetQueryService(repository);

    @Test
    void groupsTheCompleteBoundedChildRelationAndRequestsAOneThousandAndOneRowSentinel() {
        List<Long> articleIds = List.of(10L, 20L);
        when(repository.findByArticleIdIn(eq(articleIds), any(Pageable.class))).thenReturn(List.of(
                row(10L, "All"), row(20L, "ოფისი"), row(20L, "ტექნიკური")));

        Map<Long, List<String>> result = service.targetDepartmentsByArticleWithinLimit(articleIds);

        assertEquals(List.of("All"), result.get(10L));
        assertEquals(List.of("ოფისი", "ტექნიკური"), result.get(20L));
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByArticleIdIn(eq(articleIds), page.capture());
        assertEquals(CompleteResultGuard.MAX_ROWS + 1, page.getValue().getPageSize());
    }

    @Test
    void rejectsTheMultiParentChildCrossProductInsteadOfReturningAPartialMap() {
        List<Long> articleIds = List.of(10L, 20L);
        when(repository.findByArticleIdIn(eq(articleIds), any(Pageable.class))).thenReturn(
                Collections.nCopies(CompleteResultGuard.MAX_ROWS + 1, row(10L, "All")));

        assertThrows(CompleteResultGuard.CompleteResultCardinalityExceededException.class,
                () -> service.targetDepartmentsByArticleWithinLimit(articleIds));
    }

    @Test
    void anEmptyParentSetAvoidsAnInvalidInQuery() {
        assertEquals(Map.of(), service.targetDepartmentsByArticleWithinLimit(List.of()));
        verify(repository, never()).findByArticleIdIn(any(), any());
    }

    private static ArticleTargetDepartment row(Long articleId, String department) {
        ArticleTargetDepartment row = new ArticleTargetDepartment();
        row.setArticleId(articleId);
        row.setDepartment(department);
        return row;
    }
}
