package ge.magti.portal.article;

import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.User;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;

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
        // is_draft is the personal-autosave flag, not editorial state: it
        // hides a row from everyone but its author, content administrators
        // included. This clause was missing until 2026-09-06, and its absence
        // did not show up as a wrong list -- ArticleQueryService's SQL carries
        // the same rule and the lists were correct -- but as a weaker answer
        // on the two callers that ask this class instead: /uploads/{filename}
        // (DEC-P01, enforcing in production) served a still-private draft's
        // attachments to anyone in its target departments, and the note
        // endpoints treated that draft as readable. Ordered before the
        // content-admin bypass on purpose; the list query hides another
        // author's draft from administrators too, and two answers to one
        // question is what this class exists to prevent.
        if (article.isDraft() && !isAuthor(article, user)) {
            return false;
        }
        if (user.getRole().isContentAdmin()) {
            return true;
        }
        if (!DepartmentMatcher.matches(user.getDepartment(), targetDepartments)) {
            return false;
        }
        if ("published".equals(article.getStatus())) {
            return true;
        }
        // A scheduled article is readable once its moment has passed. The
        // status column is not flipped by anything, so the date is the truth.
        return "scheduled".equals(article.getStatus())
                && article.getPublishedAt() != null
                && !article.getPublishedAt().isAfter(TbilisiTime.now());
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
