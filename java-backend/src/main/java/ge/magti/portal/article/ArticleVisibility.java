package ge.magti.portal.article;

import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.User;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The single rule for "may this person read this article".
 *
 * <p>Lifted out of {@code ArticleController}, where it was a private static
 * helper, because a second caller now needs the same answer:
 * {@code /uploads/{filename}} has to decide whether the content carrying a
 * file is readable before handing the file over (DEC-P01). Two copies of this
 * predicate would be two answers, and the interesting failure is the quiet
 * one -- a file staying reachable after the article around it stopped being.
 *
 * <p>This project has been here before. {@code ManagerScope} exists because
 * "which users may a manager see" had been answered independently in five
 * places; {@code SEC-13} is that story. One class, one rule, both callers.
 */
public final class ArticleVisibility {

    private ArticleVisibility() {
    }

    /**
     * @param targetDepartments the article's audience, already resolved --
     *                          callers differ in how they load it (one row at
     *                          a time, or batched for a list), so it is passed
     *                          in rather than fetched here.
     */
    public static boolean isVisible(Article article, List<String> targetDepartments, User user) {
        // is_draft is the personal-autosave flag, not editorial state. This
        // clause was missing entirely until 2026-09-06, and its absence did
        // not show up as a wrong list -- ArticleQueryService's SQL carries
        // the same rule, so every list was correct -- but as a weaker answer
        // everywhere this class is asked instead of that query. That is ten
        // endpoints in ArticleController, through assertArticleVisible and
        // requireVisibleArticle, plus /uploads/{filename} via FileAccessPolicy
        // (DEC-P01, enforcing in production). The one that mattered is
        // GET /api/articles/{id}: another author's private draft, if its
        // status happened to say published, came back in full to any of the
        // ~600 operators in its target departments.
        if (article.isDraft() && !isAuthor(article, user)) {
            return false;
        }
        if (user.getRole().isContentAdmin()) {
            return true;
        }
        if (!DepartmentMatcher.matches(user.getDepartment(), targetDepartments)) {
            return false;
        }
        // Ownership is already settled above, so what is left is the
        // status-and-date clause alone.
        return isPublishedByLifecycle(article.getStatus(), article.getPublishedAt());
    }

    /**
     * The status-and-date half of the rule, with no opinion about
     * {@code is_draft} or about audience.
     *
     * <p>Extracted so that the three backend places that need this exact
     * question share one answer. It had been written out three times: here,
     * in {@code ArticleController#isReaderVisible} (which guards autosave),
     * and implicitly in {@code ArticleQueryService}'s JPQL. The SQL copy has
     * to stay -- the database cannot call this -- but it sits beside the
     * fixture that pins both, and the other two now do not.
     *
     * <p>A scheduled article becomes readable once its moment has passed:
     * nothing flips the {@code status} column on a timer, so the date is the
     * truth. Unrecognised statuses are invisible; this allow-lists rather
     * than deny-lists, so a status added later stays hidden until it is
     * handled here on purpose.
     */
    public static boolean isPublishedByLifecycle(String status, OffsetDateTime publishedAt) {
        if ("published".equals(status)) {
            return true;
        }
        return "scheduled".equals(status)
                && publishedAt != null
                && !publishedAt.isAfter(TbilisiTime.now());
    }

    /**
     * Null-safe on both sides, and deliberately so: an article with no author
     * belongs to nobody rather than to everybody, which is the same direction
     * {@code ArticleQueryService}'s {@code a.authorId = :userId} resolves to
     * for a null column.
     */
    private static boolean isAuthor(Article article, User user) {
        return article.getAuthorId() != null && article.getAuthorId().equals(user.getId());
    }
}
