package ge.magti.portal.article;

import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.news.NewsVisibility;
import ge.magti.portal.video.VideoVisibility;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading follows {@code content.manage}, not the role (audit 2026-10-01,
 * owner's decision the same day).
 *
 * <p>A manager an administrator had granted {@code content.manage} and
 * {@code articles.edit} got a 404 opening a colleague's unpublished article and
 * a 200 overwriting it. {@link User#seesAllContent()} is what the visibility
 * rules now ask; {@code JwtAuthenticationFilter} sets it from the capability on
 * every request, and these cases stand in for that with the setter.
 */
class ContentReachDelegationTest {

    private static final List<String> ANOTHER_DEPARTMENT = List.of("საინფორმაციო");

    @Test
    void aManagerGrantedContentManageReadsAnUnpublishedArticleOutsideTheirDepartment() {
        assertFalse(ArticleVisibility.isVisible(unpublished(), ANOTHER_DEPARTMENT, manager(null)),
                "without the grant the role answers, and a manager reads only published material of their own");
        assertTrue(ArticleVisibility.isVisible(unpublished(), ANOTHER_DEPARTMENT, manager(true)));
    }

    @Test
    void aContentAdministratorRefusedContentManageReadsLikeTheirDepartment() {
        User refused = user(Role.CONTENT_ADMIN, false);
        assertFalse(ArticleVisibility.isVisible(unpublished(), ANOTHER_DEPARTMENT, refused));
        assertTrue(ArticleVisibility.isVisible(unpublished(), ANOTHER_DEPARTMENT, user(Role.CONTENT_ADMIN, null)),
                "unresolved, the role still answers -- tests and data-loaded users keep today's behaviour");
    }

    @Test
    void theGrantNeverOpensSomebodyElsesPrivateDraft() {
        Article privateDraft = unpublished();
        privateDraft.setDraft(true);
        privateDraft.setAuthorId(99L);
        assertFalse(ArticleVisibility.isVisible(privateDraft, List.of("All"), manager(true)));

        News privateNews = new News();
        privateNews.setDraft(true);
        privateNews.setAuthorId(99L);
        privateNews.setTargetDepartment("All");
        assertFalse(NewsVisibility.isVisible(privateNews, manager(true)));
    }

    @Test
    void newsAndVideosFollowTheSameGrant() {
        News news = new News();
        news.setDraft(false);
        news.setTargetDepartment("საინფორმაციო");
        assertFalse(NewsVisibility.isVisible(news, manager(null)));
        assertTrue(NewsVisibility.isVisible(news, manager(true)));

        VideoInstruction archived = new VideoInstruction();
        archived.setArchived(true);
        archived.setTargetDepartment("საინფორმაციო");
        assertFalse(VideoVisibility.isVisible(archived, manager(null)));
        assertTrue(VideoVisibility.isVisible(archived, manager(true)));
    }

    private static Article unpublished() {
        Article article = new Article();
        article.setStatus("draft");
        article.setDraft(false);
        article.setAuthorId(42L);
        return article;
    }

    private static User manager(Boolean seesAllContent) {
        return user(Role.MANAGER, seesAllContent);
    }

    private static User user(Role role, Boolean seesAllContent) {
        User user = new User();
        user.setId(5L);
        user.setRole(role);
        user.setDepartment("ტექნიკური");
        user.setSeesAllContent(seesAllContent);
        return user;
    }
}
