package ge.magti.portal.domain;

import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsTest {

    @Test
    void newInstanceMatchesModelsPyColumnDefaults() {
        News news = new News();

        assertEquals("All", news.getTargetDepartment());
        assertEquals(1, news.getVersion());
        assertTrue(news.isVisibleToTechInfo());
        assertFalse(news.isVisibleToServiceCenter());
        assertTrue(news.isDraft());
    }

    @Test
    void notArchivedWhenExpiresAtIsNull() {
        News news = new News();

        assertFalse(news.isArchived());
    }

    @Test
    void archivedWhenExpiresAtIsInThePast() {
        News news = new News();
        news.setExpiresAt(TbilisiTime.now().minusDays(1));

        assertTrue(news.isArchived());
    }

    @Test
    void notArchivedWhenExpiresAtIsInTheFuture() {
        News news = new News();
        news.setExpiresAt(TbilisiTime.now().plusDays(1));

        assertFalse(news.isArchived());
    }
}
