package ge.magti.portal.news;

import ge.magti.portal.domain.News;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsVisibilityTest {
    @Test
    void privateNewsRequiresItsContentAdminAuthor() {
        var news = news(true, "All");
        assertTrue(NewsVisibility.isVisible(news, user(1L, Role.CONTENT_ADMIN, "სხვა")));
        assertFalse(NewsVisibility.isVisible(news, user(2L, Role.CONTENT_ADMIN, "All")));
        assertFalse(NewsVisibility.isVisible(news, user(2L, Role.SYSTEM_ADMIN, "All")));
        assertFalse(NewsVisibility.isVisible(news, user(1L, Role.OPERATOR, "ტექნიკური")));
    }

    @Test
    void publishedNewsUsesSharedDepartmentRulesAndAdminBypass() {
        var news = news(false, "ტექნიკური");
        assertTrue(NewsVisibility.isVisible(news, user(2L, Role.OPERATOR, "ტექნიკური — ჯგუფი 03")));
        assertFalse(NewsVisibility.isVisible(news, user(2L, Role.OPERATOR, "საინფორმაციო")));
        assertFalse(NewsVisibility.isVisible(news, user(2L, Role.OPERATOR, null)));
        assertTrue(NewsVisibility.isVisible(news, user(2L, Role.CONTENT_ADMIN, null)));
        news.setTargetDepartment("All");
        assertTrue(NewsVisibility.isVisible(news, user(2L, Role.OPERATOR, null)));
        news.setTargetDepartment(null);
        assertFalse(NewsVisibility.isVisible(news, user(2L, Role.OPERATOR, null)));
    }

    @Test
    void archivedNewsIsInvisibleToOperatorsButAvailableToContentAdministrators() {
        var news = news(false, "All");
        news.setExpiresAt(ge.magti.portal.util.TbilisiTime.now().minusDays(1));

        assertFalse(NewsVisibility.isVisible(news, user(2L, Role.OPERATOR, "All")));
        assertTrue(NewsVisibility.isVisible(news, user(2L, Role.CONTENT_ADMIN, "All")));
    }

    private static News news(boolean draft, String target) {
        var news = new News();
        news.setDraft(draft);
        news.setAuthorId(1L);
        news.setTargetDepartment(target);
        return news;
    }

    private static User user(Long id, Role role, String department) {
        var user = new User();
        user.setId(id);
        user.setRole(role);
        user.setDepartment(department);
        return user;
    }
}
