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

    // -- PO-54: out of the archive goes back to where it was archived from ----

    private static final java.time.OffsetDateTime NOW =
            java.time.OffsetDateTime.parse("2026-10-03T12:00:00+04:00");

    private static Article archivedFrom(String status, java.time.OffsetDateTime publishedAt) {
        Article article = new Article();
        article.setStatus(status);
        article.setPublishedAt(publishedAt);
        article.setStatus("archived");
        return article;
    }

    @Test
    void archivingRemembersTheStatusItLeftAndLeavingClearsIt() {
        Article article = archivedFrom("scheduled", NOW.plusDays(7));
        assertEquals("scheduled", article.getStatusBeforeArchive());
        article.setStatus("archived");
        assertEquals("scheduled", article.getStatusBeforeArchive(), "archiving twice keeps the first state");
        article.setStatus(article.statusAfterArchive(NOW));
        assertEquals("scheduled", article.getStatus());
        assertEquals(null, article.getStatusBeforeArchive());
    }

    @Test
    void anArchivedDraftComesBackADraft() {
        assertEquals("draft", archivedFrom("draft", null).statusAfterArchive(NOW));
    }

    @Test
    void anArchivedScheduledArticleComesBackScheduledEvenAfterItsMomentPassed() {
        // Its moment passing makes it readable through the lifecycle rule
        // (ArticleVisibility), with the status it was given.
        assertEquals("scheduled", archivedFrom("scheduled", NOW.minusDays(1)).statusAfterArchive(NOW));
    }

    @Test
    void anArchivedPublishedArticleComesBackPublished() {
        assertEquals("published", archivedFrom("published", NOW.minusDays(30)).statusAfterArchive(NOW));
    }

    @Test
    void anArticleArchivedBeforeV53IsJudgedByItsPublicationDate() {
        Article legacy = new Article();
        legacy.setPublishedAt(null);
        // Loaded from the database: no setStatus call, so nothing remembered.
        org.springframework.test.util.ReflectionTestUtils.setField(legacy, "status", "archived");
        assertEquals("draft", legacy.statusAfterArchive(NOW));
        legacy.setPublishedAt(NOW.plusDays(2));
        assertEquals("scheduled", legacy.statusAfterArchive(NOW));
        legacy.setPublishedAt(NOW.minusDays(2));
        assertEquals("published", legacy.statusAfterArchive(NOW));
    }
}
