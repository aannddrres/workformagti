package ge.magti.portal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleTest {

    @Test
    void newInstanceMatchesModelsPyColumnDefaults() {
        Article article = new Article();

        assertEquals("All", article.getTargetDepartment());
        assertTrue(article.getTargetDepartments().isEmpty());
        assertEquals("all", article.getAudienceProfile());
        assertEquals(1, article.getVersion());
        assertEquals("draft", article.getStatus());
        assertTrue(article.isVisibleToTechInfo());
        assertFalse(article.isVisibleToServiceCenter());
        assertTrue(article.isDraft());
        assertFalse(article.isQuizEnabled());
    }

    @Test
    void targetDepartmentAndTargetDepartmentsAreIndependentFields() {
        Article article = new Article();
        article.setTargetDepartments(java.util.List.of("ტექნიკური", "საინფორმაციო"));

        assertEquals("All", article.getTargetDepartment());
        assertEquals(2, article.getTargetDepartments().size());
    }
}
