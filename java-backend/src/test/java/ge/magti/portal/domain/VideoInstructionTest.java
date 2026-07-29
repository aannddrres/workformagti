package ge.magti.portal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VideoInstructionTest {

    @Test
    void newInstanceMatchesModelsPyColumnDefaults() {
        VideoInstruction video = new VideoInstruction();

        assertEquals("All", video.getTargetDepartment());
        assertEquals(0, video.getViewsCount());
        assertFalse(video.isArchived());
    }
}
