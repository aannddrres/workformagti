package ge.magti.portal.article;

import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.user.UserDirectoryQueryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EligibleOperatorsServiceTest {

    private final UserDirectoryQueryService directory = mock(UserDirectoryQueryService.class);
    private final EligibleOperatorsService service = new EligibleOperatorsService(directory);

    @Test
    void allAudienceUsesTheBoundedActiveSnapshotAndKeepsEligibilityFiltering() {
        User operator = user(1L, Role.OPERATOR);
        User manager = user(2L, Role.MANAGER);
        when(directory.listActiveUsersWithinLimit()).thenReturn(List.of(operator, manager));

        List<User> result = service.forArticle(publishedArticle(), List.of("All"));

        assertEquals(List.of(operator), result);
        verify(directory).listActiveUsersWithinLimit();
        verify(directory, never()).listActiveUsersInDepartmentsWithinLimit(List.of("All"));
    }

    @Test
    void exactDepartmentAudienceUsesTheBoundedFilteredSnapshot() {
        List<String> departments = List.of("ტექნიკური — ჯგუფი 01");
        User operator = user(1L, Role.OPERATOR);
        when(directory.listActiveUsersInDepartmentsWithinLimit(departments)).thenReturn(List.of(operator));

        assertEquals(List.of(operator), service.forArticle(publishedArticle(), departments));
        verify(directory).listActiveUsersInDepartmentsWithinLimit(departments);
        verify(directory, never()).listActiveUsersWithinLimit();
    }

    private static Article publishedArticle() {
        Article article = new Article();
        article.setStatus("published");
        article.setDraft(false);
        return article;
    }

    private static User user(long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setActive(true);
        return user;
    }
}
