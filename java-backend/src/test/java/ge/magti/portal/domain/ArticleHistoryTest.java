package ge.magti.portal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

class ArticleHistoryTest {

    @Test
    void versionIdIsNullByDefaultNotZero() {
        ArticleHistory history = new ArticleHistory();

        assertNull(history.getVersionId());
    }
}
