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
            return !news.isDraft() || Objects.equals(news.getAuthorId(), user.getId());
        }
        return !news.isDraft() && !news.isArchived() && news.getTargetDepartment() != null
                && DepartmentMatcher.matches(user.getDepartment(), List.of(news.getTargetDepartment()));
    }
}
