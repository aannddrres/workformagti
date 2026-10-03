package ge.magti.portal.video;

import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.VideoInstruction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoVisibilityTest {
    @Test
    void audienceUsesDepartmentPrefixWildcardAndContentAdminRole() {
        var video = new VideoInstruction();
        video.setTargetDepartment("ტექნიკური");
        var user = new User();
        user.setRole(Role.OPERATOR);
        user.setDepartment("ტექნიკური — ჯგუფი 03");
        assertTrue(VideoVisibility.isInAudience(video, user));
        video.setTargetDepartment("ტექნიკური — ჯგუფი 03");
        assertTrue(VideoVisibility.isInAudience(video, user));
        video.setTargetDepartment("ტექნიკური — ჯგუფი 04");
        assertFalse(VideoVisibility.isInAudience(video, user));
        user.setDepartment("საინფორმაციო");
        assertFalse(VideoVisibility.isInAudience(video, user));
        user.setDepartment(null);
        assertFalse(VideoVisibility.isInAudience(video, user));
        video.setTargetDepartment("All");
        assertTrue(VideoVisibility.isInAudience(video, user));
        video.setTargetDepartment(null);
        assertFalse(VideoVisibility.isInAudience(video, user));
        user.setRole(Role.CONTENT_ADMIN);
        assertTrue(VideoVisibility.isInAudience(video, user));
    }

    /** Mutation testing, 2026-10-02: nothing DB-free noticed an archived video staying visible to its audience. */
    @Test
    void anArchivedVideoLeavesItsAudienceButNotItsEditors() {
        var video = new VideoInstruction();
        video.setTargetDepartment("ტექნიკური");
        var operator = new User();
        operator.setRole(Role.OPERATOR);
        operator.setDepartment("ტექნიკური — ჯგუფი 03");
        var editor = new User();
        editor.setRole(Role.CONTENT_ADMIN);
        assertTrue(VideoVisibility.isVisible(video, operator));

        video.setArchived(true);

        assertFalse(VideoVisibility.isVisible(video, operator));
        assertTrue(VideoVisibility.isVisible(video, editor));
    }
}
