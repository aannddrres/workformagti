package ge.magti.portal.video;

import ge.magti.portal.domain.User;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.util.DepartmentMatcher;

import java.util.List;

/** Who may see a video: its audience, and whether it is archived. */
public final class VideoVisibility {
    private VideoVisibility() {
    }

    /**
     * The rule the video endpoints apply: an archived video is for content
     * administrators only, the rest for its audience. It was a private helper
     * in VideoController; a bookmark's title now asks the same question
     * (A17), so it lives here. FileAccessPolicy keeps its own archive check
     * and asks {@link #isInAudience} alone.
     */
    public static boolean isVisible(VideoInstruction video, User user) {
        return user.getRole().isContentAdmin() || (!video.isArchived() && isInAudience(video, user));
    }

    public static boolean isInAudience(VideoInstruction video, User user) {
        return user.getRole().isContentAdmin() || (video.getTargetDepartment() != null
                && DepartmentMatcher.matches(user.getDepartment(), List.of(video.getTargetDepartment())));
    }
}
