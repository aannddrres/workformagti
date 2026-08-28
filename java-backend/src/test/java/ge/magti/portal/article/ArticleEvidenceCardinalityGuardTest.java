package ge.magti.portal.article;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArticleEvidenceCardinalityGuardTest {

    @Test
    void exactCeilingIsAcceptedWithoutCopying() {
        List<Integer> rows = rows(ArticleEvidenceCardinalityGuard.MAX_ROWS);

        assertSame(rows, ArticleEvidenceCardinalityGuard.enforceWithinLimit(rows));
        ArticleEvidenceCardinalityGuard.enforceResponseRowLimit(rows.size());
    }

    @Test
    void sentinelOrCombinedResponseOverflowFailsLoudly() {
        List<Integer> rows = rows(ArticleEvidenceCardinalityGuard.MAX_ROWS + 1);

        assertThrows(ArticleEvidenceCardinalityGuard.ArticleEvidenceCardinalityExceededException.class,
                () -> ArticleEvidenceCardinalityGuard.enforceWithinLimit(rows));
        assertThrows(ArticleEvidenceCardinalityGuard.ArticleEvidenceCardinalityExceededException.class,
                () -> ArticleEvidenceCardinalityGuard.enforceResponseRowLimit(rows.size()));
    }

    private static List<Integer> rows(int size) {
        return IntStream.range(0, size).boxed().toList();
    }
}
