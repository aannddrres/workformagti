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
}
