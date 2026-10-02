package ge.magti.portal.article;

import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.user.UserDirectoryQueryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
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
    }

    @Test
    void aDepartmentAudienceReachesItsGroupsAsTheArticleItselfDoes() {
        User inDepartment = user(1L, Role.OPERATOR, "ტექნიკური");
        User inGroup = user(2L, Role.OPERATOR, "ტექნიკური — ჯგუფი 03");
        User elsewhere = user(3L, Role.OPERATOR, "საინფორმაციო — ჯგუფი 01");
        User manager = user(4L, Role.MANAGER, "ტექნიკური");
        when(directory.listActiveUsersWithinLimit()).thenReturn(List.of(inDepartment, inGroup, elsewhere, manager));

        assertEquals(List.of(inDepartment, inGroup), service.forArticle(publishedArticle(), List.of("ტექნიკური")));
        assertEquals(List.of(inGroup), service.forArticle(publishedArticle(), List.of("ტექნიკური — ჯგუფი 03")));
    }

    private static Article publishedArticle() {
        Article article = new Article();
        article.setStatus("published");
        article.setDraft(false);
        return article;
    }

    private static User user(long id, Role role) {
        return user(id, role, "All");
    }

    private static User user(long id, Role role, String department) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(true);
        return user;
    }
}
