package ge.magti.portal.article;

import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code is_draft} half of {@link ArticleVisibility}, and the reason it is
 * tested here rather than only in the shared fixture.
 *
 * <h2>The defect this pins</h2>
 *
 * Until 2026-09-06 {@link ArticleVisibility} did not look at {@code is_draft}
 * at all. That did not show up as a wrong article list, because
 * {@code ArticleQueryService}'s SQL carries the same rule in its own
 * {@code WHERE} clause and every list was correct. It showed up everywhere
 * this class is asked instead of that query — eleven endpoints:
 *
 * <ul>
 *   <li><b>{@code GET /api/articles/{id}}</b>, through
 *       {@code ArticleController#assertArticleVisible}. An article carrying
 *       {@code is_draft = true} together with {@code status = 'published'} was
 *       hidden from every list and returned <b>in full</b> by id, to anyone in
 *       its target departments. This is the one that matters, and it was
 *       under-stated as "the note endpoints" when the defect was first written
 *       up; the record is corrected here rather than quietly.
 *   <li>Eight more reaching the same helper — {@code /related},
 *       {@code /versions}, the history {@code /diff}, {@code /read-receipt}
 *       and its {@code /me}, {@code /view}, {@code /me/recently-viewed}, and
 *       both {@code /note} routes.
 *   <li>{@code storage/FileAccessPolicy} — {@code /uploads/{filename}}, the
 *       DEC-P01 entitlement gate, <b>enforcing in production</b>
 *       ({@code ROLLOUT_FILE_ENTITLEMENT=true}), which served that draft's
 *       attachments on the same reasoning.
 * </ul>
 *
 * <p>That pair of values is not exotic. {@code ArticleRequest.isDraftOrDefault}
 * returns {@code true} when the field is absent, and nothing couples it to
 * {@code status}, so any caller that publishes without saying
 * {@code is_draft: false} creates it. The Angular editor does say so; a seeder,
 * an import script or a screen written next year need not.
 *
 * <h2>Why not just add cases to the shared fixture</h2>
 *
 * Two of the three cases below <i>are</i> in
 * {@code docs/api-contract/article-visibility-cases.json} now, so the Angular
 * mirror is held to them too. The author's own draft cannot go there: a list
 * screen filters rows it has already been given and has no author to compare
 * against, so there is no second implementation to keep in step. It is a
 * backend-only clause, and this is where a backend-only clause belongs.
 */
class ArticleVisibilityDraftTest {

    private static final long AUTHOR_ID = 7L;
    private static final long SOMEBODY_ELSE = 8L;
    private static final List<String> TARGETED_AT_EVERYONE = List.of("All");

    @Test
    void anotherPersonsDraftIsInvisibleEvenWhenItsStatusSaysPublished() {
        assertFalse(ArticleVisibility.isVisible(
                draftBySomebodyElse("published"), TARGETED_AT_EVERYONE, operator(AUTHOR_ID)),
                "is_draft is the personal-autosave flag, so it outranks status. Until 2026-09-06 this "
                        + "exact pair came back in full from GET /api/articles/{id} to anyone in the "
                        + "target departments, and its attachments from /uploads/{filename}.");
    }

    @Test
    void aContentAdministratorCannotOpenAnotherAuthorsDraft() {
        for (Role role : List.of(Role.CONTENT_ADMIN, Role.SYSTEM_ADMIN)) {
            User admin = new User();
            admin.setId(AUTHOR_ID);
            admin.setRole(role);
            admin.setDepartment("ტექნიკური");
            assertFalse(ArticleVisibility.isVisible(
                    draftBySomebodyElse("published"), TARGETED_AT_EVERYONE, admin),
                    role + " must obey the same author-only rule as ArticleQueryService's list");
        }
    }

    @Test
    void yourOwnDraftStaysVisibleToYou() {
        Article mine = draftBySomebodyElse("published");
        mine.setAuthorId(AUTHOR_ID);
        assertTrue(ArticleVisibility.isVisible(mine, TARGETED_AT_EVERYONE, operator(AUTHOR_ID)),
                "the author is the one person a personal autosave is for; "
                        + "ArticleQueryService's OR a.authorId = :userId says the same thing.");
    }

    /**
     * Both sides of the comparison are nullable in this schema —
     * {@code articles.author_id} and, for a caller assembled outside the JWT
     * filter, {@code users.id}. Neither may resolve to "everybody's draft".
     */
    @Test
    void aDraftWithNoAuthorBelongsToNobody() {
        Article orphan = draftBySomebodyElse("published");
        orphan.setAuthorId(null);
        assertFalse(ArticleVisibility.isVisible(orphan, TARGETED_AT_EVERYONE, operator(AUTHOR_ID)),
                "a null author must fail closed, matching how a null column answers "
                        + "a.authorId = :userId in the list query");

        User anonymous = operator(AUTHOR_ID);
        anonymous.setId(null);
        assertFalse(ArticleVisibility.isVisible(
                draftBySomebodyElse("published"), TARGETED_AT_EVERYONE, anonymous),
                "a caller with no id must fail closed too, rather than throw");
    }

    /**
     * Guards the guard: every assertion above is negative, and a rule that
     * returned {@code false} unconditionally would satisfy all of them while
     * making the whole knowledge base unreadable.
     */
    @Test
    void anOrdinaryPublishedArticleIsStillVisible() {
        Article published = new Article();
        published.setStatus("published");
        published.setDraft(false);
        published.setAuthorId(SOMEBODY_ELSE);
        assertTrue(ArticleVisibility.isVisible(published, TARGETED_AT_EVERYONE, operator(AUTHOR_ID)),
                "the draft clause must not have swallowed ordinary published content");
    }

    private static Article draftBySomebodyElse(String status) {
        Article article = new Article();
        article.setStatus(status);
        article.setDraft(true);
        article.setAuthorId(SOMEBODY_ELSE);
        article.setPublishedAt(TbilisiTime.now().minusDays(1));
        return article;
    }

    private static User operator(long id) {
        User user = new User();
        user.setId(id);
        user.setRole(Role.OPERATOR);
        user.setDepartment("ტექნიკური");
        return user;
    }
}
