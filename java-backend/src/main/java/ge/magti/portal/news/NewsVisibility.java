package ge.magti.portal.news;

import ge.magti.portal.domain.News;
import ge.magti.portal.domain.User;
import ge.magti.portal.util.DepartmentMatcher;

import java.util.List;
import java.util.Objects;

/** Shared author/draft/audience rule. Archived news is reader-hidden. */
public final class NewsVisibility {
    private NewsVisibility() {
    }

    public static boolean isVisible(News news, User user) {
        if (user.getRole().isContentAdmin()) {
            return !isPrivateDraftOfAnother(news, user);
        }
        return !news.isDraft() && !news.isArchived() && news.getTargetDepartment() != null
                && DepartmentMatcher.matches(user.getDepartment(), List.of(news.getTargetDepartment()));
    }

    /**
     * The draft half alone: is_draft set, and the caller is not the author.
     * For the endpoints that change or reveal one news item without applying
     * the reader rules (PO-34, D2); an item with no author is nobody's.
     */
    public static boolean isPrivateDraftOfAnother(News news, User user) {
        return news.isDraft() && !Objects.equals(news.getAuthorId(), user.getId());
    }
}
