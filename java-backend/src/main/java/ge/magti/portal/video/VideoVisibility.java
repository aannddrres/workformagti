package ge.magti.portal.video;

import ge.magti.portal.domain.User;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.util.DepartmentMatcher;

import java.util.List;

/** Audience only: file and controller retain their distinct archive restrictions. */
public final class VideoVisibility {
    private VideoVisibility() {
    }

    public static boolean isInAudience(VideoInstruction video, User user) {
        return user.getRole().isContentAdmin() || (video.getTargetDepartment() != null
                && DepartmentMatcher.matches(user.getDepartment(), List.of(video.getTargetDepartment())));
    }
}
