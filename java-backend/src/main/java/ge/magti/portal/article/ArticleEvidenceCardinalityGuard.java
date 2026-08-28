package ge.magti.portal.article;

import java.util.List;

/** Fail-loud ceiling for complete-result article history/evidence responses. */
public final class ArticleEvidenceCardinalityGuard {

    public static final int MAX_ROWS = 1_000;

    private ArticleEvidenceCardinalityGuard() {
    }

    public static <T> List<T> enforceWithinLimit(List<T> rows) {
        if (rows.size() > MAX_ROWS) {
            throw new ArticleEvidenceCardinalityExceededException();
        }
        return rows;
    }

    public static void enforceResponseRowLimit(int size) {
        if (size > MAX_ROWS) {
            throw new ArticleEvidenceCardinalityExceededException();
        }
    }

    public static class ArticleEvidenceCardinalityExceededException extends RuntimeException {
        public ArticleEvidenceCardinalityExceededException() {
            super("Article evidence exceeds the bounded complete-result response");
        }
    }
}
